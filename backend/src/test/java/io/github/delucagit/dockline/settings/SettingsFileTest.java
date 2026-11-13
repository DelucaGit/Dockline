package io.github.delucagit.dockline.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.delucagit.dockline.log.LogEntry;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** The settings file itself: versions and upgrades, missing and unusable files, and safe writes. */
class SettingsFileTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-13T10:15:30Z"), ZoneOffset.UTC);
    private static final String BACKUP_NAME = "settings.corrupt-20261113T101530Z.json";

    @TempDir
    Path dir;

    private final ProblemLog problemLog = new ProblemLog("test");

    @Test
    void aMissingFileMeansEmptySettingsAndNothingIsWrittenOrLogged() {
        SettingsFile settings = open();

        assertThat(json(settings)).isEqualTo("{\"version\":1}");
        assertThat(filesInDir()).isEmpty();
        assertThat(problemLog.recent(null, null)).isEmpty();
    }

    @Test
    void aChangeIsWrittenWithTheVersionAndReadAgainAfterARestart() throws Exception {
        // The folder does not have to exist yet.
        Path file = dir.resolve("Dockline").resolve("settings.json");
        new SettingsFile(file, 1, List.of(), problemLog, CLOCK).update(root -> root.put("theme", "dark"));

        JsonNode written = JSON.readTree(Files.readAllBytes(file));
        assertThat(written.get("version").intValue()).isEqualTo(1);
        assertThat(written.get("theme").stringValue()).isEqualTo("dark");

        SettingsFile restarted = new SettingsFile(file, 1, List.of(), problemLog, CLOCK);
        assertThat(theme(restarted)).isEqualTo("dark");
        assertThat(problemLog.recent(null, null)).isEmpty();
    }

    @Test
    void propertiesThisVersionDoesNotKnowAreKept() throws Exception {
        write("{\"version\":1,\"somethingNew\":{\"a\":[1,2]}}");

        open().update(root -> root.put("theme", "dark"));

        JsonNode written = JSON.readTree(Files.readAllBytes(file()));
        assertThat(written.get("somethingNew").toString()).isEqualTo("{\"a\":[1,2]}");
        assertThat(written.get("theme").stringValue()).isEqualTo("dark");
    }

    @Test
    void anOlderVersionIsUpgradedStepByStepWhenReadAndSavedInTheNewFormat() throws Exception {
        write("{\"version\":1,\"name\":\"dockline\"}");
        UnaryOperator<ObjectNode> oneToTwo = root -> {
            root.put("title", root.remove("name").stringValue());
            return root;
        };
        UnaryOperator<ObjectNode> twoToThree = root -> {
            root.put("title", root.get("title").stringValue().toUpperCase());
            return root;
        };

        SettingsFile settings = new SettingsFile(file(), 3, List.of(oneToTwo, twoToThree), problemLog, CLOCK);

        assertThat(json(settings)).isEqualTo("{\"version\":3,\"title\":\"DOCKLINE\"}");
        assertThat(JSON.readTree(Files.readAllBytes(file())).toString())
                .isEqualTo("{\"version\":3,\"title\":\"DOCKLINE\"}");
        assertThat(filesInDir()).containsExactly("settings.json");
        assertThat(problemLog.recent(null, null)).isEmpty();
    }

    @Test
    void onlyTheStepsAfterTheFilesVersionAreApplied() throws Exception {
        write("{\"version\":2,\"title\":\"dockline\"}");
        UnaryOperator<ObjectNode> oneToTwo = root -> {
            throw new AssertionError("a version 2 file must not go through the 1 -> 2 step");
        };
        UnaryOperator<ObjectNode> twoToThree = root -> {
            root.put("upgraded", true);
            return root;
        };

        SettingsFile settings = new SettingsFile(file(), 3, List.of(oneToTwo, twoToThree), problemLog, CLOCK);

        assertThat(json(settings))
                .isEqualTo("{\"version\":3,\"title\":\"dockline\",\"upgraded\":true}");
    }

    @Test
    void aFailingUpgradeKeepsTheOldFileAsABackupAndStartsEmpty() throws Exception {
        String old = "{\"version\":1,\"name\":\"dockline\"}";
        write(old);
        UnaryOperator<ObjectNode> broken = root -> {
            throw new IllegalStateException("cannot upgrade");
        };

        SettingsFile settings = new SettingsFile(file(), 2, List.of(broken), problemLog, CLOCK);

        assertThat(json(settings)).isEqualTo("{\"version\":2}");
        assertThat(Files.readString(dir.resolve(BACKUP_NAME))).isEqualTo(old);
        assertThat(onlyLogEntry().message()).contains("upgrading it from settings version 1 to 2 failed");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "this is not JSON",
        "{\"version\":1,\"siteRepoLinks\":{\"example.com\":\"own",   // cut off in the middle
        "",
        "[1,2,3]",
        "{\"siteRepoLinks\":{}}",                                    // no version
        "{\"version\":\"one\"}",
        "{\"version\":0}",
        "{\"version\":2,\"fromTheFuture\":true}"                     // written by a newer Dockline
    })
    void anUnusableFileStartsEmptyIsKeptAsABackupAndLogsAWarning(String content) throws Exception {
        write(content);

        SettingsFile settings = open();

        assertThat(json(settings)).isEqualTo("{\"version\":1}");
        assertThat(filesInDir()).containsExactly(BACKUP_NAME);
        assertThat(Files.readString(dir.resolve(BACKUP_NAME))).isEqualTo(content);
        LogEntry warning = onlyLogEntry();
        assertThat(warning.level()).isEqualTo(LogLevel.WARN);
        assertThat(warning.source()).isEqualTo(LogSource.BACKEND);
        assertThat(warning.action()).isEqualTo("Read settings file");
        assertThat(warning.message()).contains(file().toString()).contains("starts with empty settings")
                .contains(BACKUP_NAME);

        // The app keeps working: the next change creates a fresh, valid file and the backup stays.
        settings.update(root -> root.put("theme", "dark"));
        assertThat(filesInDir()).containsExactlyInAnyOrder("settings.json", BACKUP_NAME);
        assertThat(theme(open())).isEqualTo("dark");
    }

    @Test
    void aSecondUnusableFileInTheSameSecondGetsItsOwnBackup() throws Exception {
        write("first");
        open();
        write("second");
        open();

        assertThat(Files.readString(dir.resolve(BACKUP_NAME))).isEqualTo("first");
        assertThat(Files.readString(dir.resolve("settings.corrupt-20261113T101530Z-2.json"))).isEqualTo("second");
    }

    @Test
    void savingLeavesNoTemporaryFileBehind() {
        SettingsFile settings = open();
        settings.update(root -> root.put("a", 1));
        settings.update(root -> root.put("b", 2));

        assertThat(filesInDir()).containsExactly("settings.json");
    }

    @Test
    void aHalfWrittenTemporaryFileFromACrashIsIgnoredAndReplaced() throws Exception {
        // What a crash in the middle of a save leaves behind: the old file, intact, and a partial temp file.
        write("{\"version\":1,\"theme\":\"dark\"}");
        Files.writeString(dir.resolve("settings.json.tmp"), "{\"version\":1,\"theme\":\"li");

        SettingsFile settings = open();

        assertThat(theme(settings)).isEqualTo("dark");
        assertThat(problemLog.recent(null, null)).isEmpty();

        settings.update(root -> root.put("theme", "light"));
        assertThat(filesInDir()).containsExactly("settings.json");
        assertThat(theme(open())).isEqualTo("light");
    }

    @Test
    void whenSavingFailsTheFileAndTheSettingsInMemoryStayAsTheyWere() throws Exception {
        String before = "{\"version\":1,\"theme\":\"dark\"}";
        write(before);
        SettingsFile settings = open();
        // Nothing can be written to the temporary file while a folder is in its place.
        Files.createDirectory(dir.resolve("settings.json.tmp"));

        assertThatThrownBy(() -> settings.update(root -> root.put("theme", "light")))
                .isInstanceOf(SettingsUnavailableException.class)
                .hasMessageContaining(file().toString())
                .hasCauseInstanceOf(IOException.class);

        assertThat(Files.readString(file())).isEqualTo(before);
        assertThat(theme(settings)).isEqualTo("dark");
    }

    @Test
    void theNumberOfUpgradeStepsMustMatchTheVersion() {
        assertThatThrownBy(() -> new SettingsFile(file(), 2, List.of(), problemLog, CLOCK))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAppsOwnVersionAndUpgradeStepsFitTogether() {
        assertThat(SettingsFile.UPGRADES).hasSize(SettingsFile.CURRENT_VERSION - 1);
    }

    private SettingsFile open() {
        return new SettingsFile(file(), 1, List.of(), problemLog, CLOCK);
    }

    private static String json(SettingsFile settings) {
        return settings.read(ObjectNode::toString);
    }

    private static String theme(SettingsFile settings) {
        return settings.read(root -> root.get("theme").stringValue());
    }

    private Path file() {
        return dir.resolve("settings.json").toAbsolutePath();
    }

    private void write(String content) throws IOException {
        Files.writeString(file(), content, StandardCharsets.UTF_8);
    }

    private List<String> filesInDir() {
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(path -> path.getFileName().toString()).sorted().toList();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private LogEntry onlyLogEntry() {
        List<LogEntry> entries = problemLog.recent(null, null);
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }
}
