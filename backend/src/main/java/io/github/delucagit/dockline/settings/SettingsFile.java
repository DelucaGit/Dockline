package io.github.delucagit.dockline.settings;

import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The app's one settings file: {@code settings.json} in the folder {@code dockline.settings.dir}
 * (by default the user's app-data folder). It is a JSON object with a {@code "version"} number and one
 * property per kind of setting, for example:
 *
 * <pre>{@code
 * {
 *   "version": 1,
 *   "siteRepoLinks": { "example.com": "owner/name" }
 * }
 * }</pre>
 *
 * <ul>
 *   <li><b>Read once</b>, when the app starts; after that the content lives in memory.</li>
 *   <li><b>Missing file</b>: empty settings. The file is created by the first change.</li>
 *   <li><b>Older version</b>: upgraded step by step (see {@link #UPGRADES}) and saved in the new format.</li>
 *   <li><b>Unusable file</b> (not JSON, no version, or written by a newer Dockline): the app starts with
 *       empty settings, the file is kept next to it as {@code settings.corrupt-<time>.json}, and a warning
 *       goes to the Log section.</li>
 *   <li><b>Safe writes</b>: the new content is written to {@code settings.json.tmp}, flushed to disk and
 *       then moved over {@code settings.json} in one step. A crash in the middle leaves the old file intact.</li>
 *   <li><b>Thread-safe</b>: every read and change is synchronized.</li>
 * </ul>
 *
 * Properties this version does not know are kept as they are.
 */
@Component
class SettingsFile {

    static final String FILE_NAME = "settings.json";

    /** The format this code writes. Raise it, and add a step to {@link #UPGRADES}, when the format changes. */
    static final int CURRENT_VERSION = 1;

    /**
     * Step {@code i} turns a version {@code i + 1} file into version {@code i + 2}; it may change the
     * object it is given. Empty for now: version 1 is the first format.
     */
    static final List<UnaryOperator<ObjectNode>> UPGRADES = List.of();

    private static final String ACTION = "Read settings file";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter BACKUP_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path file;
    private final int currentVersion;
    private final List<UnaryOperator<ObjectNode>> upgrades;
    private final ProblemLog problemLog;
    private final Clock clock;

    /** The whole file, always at {@link #currentVersion}. Guarded by this. */
    private ObjectNode content;

    @Autowired
    SettingsFile(@Value("${dockline.settings.dir}") String dir, ProblemLog problemLog, Clock clock) {
        this(Path.of(dir).resolve(FILE_NAME), CURRENT_VERSION, UPGRADES, problemLog, clock);
    }

    /** For tests: any file, and any version with its upgrade steps. */
    SettingsFile(Path file, int currentVersion, List<UnaryOperator<ObjectNode>> upgrades, ProblemLog problemLog,
            Clock clock) {
        if (upgrades.size() != currentVersion - 1) {
            throw new IllegalArgumentException("Version " + currentVersion + " needs " + (currentVersion - 1)
                    + " upgrade steps, not " + upgrades.size());
        }
        this.file = file.toAbsolutePath();
        this.currentVersion = currentVersion;
        this.upgrades = List.copyOf(upgrades);
        this.problemLog = problemLog;
        this.clock = clock;
        this.content = load();
    }

    /** Reads something from the settings. The object given to {@code reader} must not be changed or kept. */
    synchronized <T> T read(Function<ObjectNode, T> reader) {
        return reader.apply(content);
    }

    /**
     * Changes the settings and saves them. {@code change} gets a copy to modify; only when that copy has
     * been written to disk does it become the current content.
     *
     * @throws SettingsUnavailableException when the file could not be written; nothing has changed then
     */
    synchronized void update(Consumer<ObjectNode> change) {
        ObjectNode changed = content.deepCopy();
        change.accept(changed);
        changed.put("version", currentVersion);
        try {
            write(changed);
        } catch (IOException | JacksonException e) {
            throw new SettingsUnavailableException("Could not save the settings file " + file, e);
        }
        content = changed;
    }

    private ObjectNode load() {
        if (Files.notExists(file)) {
            return empty();
        }
        ObjectNode read;
        int version;
        try {
            JsonNode root = JSON.readTree(Files.readAllBytes(file));
            if (!(root instanceof ObjectNode object)) {
                return startEmpty("it is not a JSON object", null);
            }
            JsonNode versionNode = object.get("version");
            if (versionNode == null || !versionNode.isInt() || versionNode.intValue() < 1) {
                return startEmpty("it has no valid \"version\" number", null);
            }
            read = object;
            version = versionNode.intValue();
        } catch (IOException | JacksonException e) {
            return startEmpty("it could not be read as JSON", e);
        }
        if (version > currentVersion) {
            return startEmpty("it was written by a newer Dockline (settings version " + version
                    + ", this Dockline knows version " + currentVersion + ")", null);
        }
        if (version == currentVersion) {
            return read;
        }
        return upgrade(read, version);
    }

    private ObjectNode upgrade(ObjectNode old, int version) {
        ObjectNode upgraded;
        try {
            upgraded = old.deepCopy();
            for (int from = version; from < currentVersion; from++) {
                upgraded = upgrades.get(from - 1).apply(upgraded);
                upgraded.put("version", from + 1);
            }
        } catch (RuntimeException e) {
            return startEmpty("upgrading it from settings version " + version + " to " + currentVersion + " failed", e);
        }
        try {
            write(upgraded);
        } catch (IOException | JacksonException e) {
            // The upgraded settings are used anyway; the next change or the next start saves them.
            problemLog.report(LogLevel.WARN, LogSource.BACKEND, ACTION, null, "The settings file " + file
                    + " was upgraded from version " + version + " to " + currentVersion
                    + " but could not be saved yet.", null, null, e);
        }
        return upgraded;
    }

    /** Moves the unusable file aside, reports it, and answers empty settings. */
    private ObjectNode startEmpty(String reason, Exception cause) {
        String kept;
        try {
            Path backup = backupPath();
            Files.move(file, backup);
            kept = "The file was kept as " + backup.getFileName() + " in the same folder.";
        } catch (IOException e) {
            kept = "It could not be moved aside as a backup (" + e + "); the next change overwrites it.";
        }
        problemLog.report(LogLevel.WARN, LogSource.BACKEND, ACTION, null, "The settings file " + file
                + " cannot be used: " + reason + ". Dockline starts with empty settings (no manual site-repo links). "
                + kept, null, null, cause);
        return empty();
    }

    private Path backupPath() {
        String base = "settings.corrupt-" + BACKUP_TIME.format(clock.instant());
        Path backup = file.resolveSibling(base + ".json");
        for (int n = 2; Files.exists(backup); n++) {
            backup = file.resolveSibling(base + "-" + n + ".json");
        }
        return backup;
    }

    private ObjectNode empty() {
        ObjectNode empty = JSON.createObjectNode();
        empty.put("version", currentVersion);
        return empty;
    }

    /** Temp file, flush to disk, then one atomic move: the settings file is always the old or the new one. */
    private void write(ObjectNode node) throws IOException {
        byte[] bytes = JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(node);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
