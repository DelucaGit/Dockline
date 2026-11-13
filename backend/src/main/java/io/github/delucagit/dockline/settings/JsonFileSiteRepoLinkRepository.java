package io.github.delucagit.dockline.settings;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Keeps the manual site–repo links in the settings file, as the object {@code "siteRepoLinks"}:
 * {@code {"example.com": "owner/name", ...}}. Entries in the file that are not a valid link are ignored.
 */
@Repository
class JsonFileSiteRepoLinkRepository implements SiteRepoLinkRepository {

    static final String PROPERTY = "siteRepoLinks";

    /** owner/name: exactly one slash, nothing empty, no spaces. */
    private static final Pattern REPO_FULL_NAME = Pattern.compile("[^/\\s]+/[^/\\s]+");

    private final SettingsFile settings;

    JsonFileSiteRepoLinkRepository(SettingsFile settings) {
        this.settings = settings;
    }

    @Override
    public Optional<String> find(String domain) {
        String key = key(domain);
        return settings.read(root -> Optional.ofNullable(repoOf(root.path(PROPERTY).get(key))));
    }

    @Override
    public Map<String, String> findAll() {
        return settings.read(root -> {
            Map<String, String> links = new TreeMap<>();
            for (Map.Entry<String, JsonNode> entry : root.path(PROPERTY).properties()) {
                String repo = repoOf(entry.getValue());
                if (repo != null && !entry.getKey().isBlank()) {
                    links.put(key(entry.getKey()), repo);
                }
            }
            return Collections.unmodifiableMap(links);
        });
    }

    @Override
    public void save(String domain, String repoFullName) {
        String key = key(domain);
        if (repoFullName == null || !REPO_FULL_NAME.matcher(repoFullName).matches()) {
            throw new IllegalArgumentException("A repository must look like owner/name, not '" + repoFullName + "'");
        }
        settings.update(root -> links(root).put(key, repoFullName));
    }

    @Override
    public boolean remove(String domain) {
        String key = key(domain);
        if (settings.read(root -> root.path(PROPERTY).get(key)) == null) {
            return false;
        }
        boolean[] removed = {false};
        settings.update(root -> removed[0] = links(root).remove(key) != null);
        return removed[0];
    }

    private static ObjectNode links(ObjectNode root) {
        return root.get(PROPERTY) instanceof ObjectNode links ? links : root.putObject(PROPERTY);
    }

    private static String repoOf(JsonNode value) {
        return value != null && value.isString() && REPO_FULL_NAME.matcher(value.stringValue()).matches()
                ? value.stringValue() : null;
    }

    private static String key(String domain) {
        if (domain == null || domain.isBlank()) {
            throw new IllegalArgumentException("A site's domain must not be blank");
        }
        return domain.strip().toLowerCase(Locale.ROOT);
    }
}
