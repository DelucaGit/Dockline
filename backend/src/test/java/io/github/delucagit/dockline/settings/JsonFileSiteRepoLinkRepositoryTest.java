package io.github.delucagit.dockline.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.delucagit.dockline.log.ProblemLog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Manual site–repo links, stored in a real settings file in a temporary folder. */
class JsonFileSiteRepoLinkRepositoryTest {

    @TempDir
    Path dir;

    private final ProblemLog problemLog = new ProblemLog("test");

    @Test
    void startsWithoutLinks() {
        SiteRepoLinkRepository links = open();

        assertThat(links.findAll()).isEmpty();
        assertThat(links.find("example.com")).isEmpty();
    }

    @Test
    void savesAndReadsLinks() {
        SiteRepoLinkRepository links = open();

        links.save("example.com", "octocat/website");
        links.save("blog.example.com", "octocat/blog");

        assertThat(links.find("example.com")).contains("octocat/website");
        assertThat(links.find("other.com")).isEmpty();
        assertThat(links.findAll()).containsExactly(
                Map.entry("blog.example.com", "octocat/blog"), Map.entry("example.com", "octocat/website"));
    }

    @Test
    void savingAgainChangesTheLinkBecauseASiteHasOneRepo() {
        SiteRepoLinkRepository links = open();
        links.save("example.com", "octocat/website");

        links.save("example.com", "octocat/new-website");

        assertThat(links.findAll()).containsExactly(Map.entry("example.com", "octocat/new-website"));
    }

    @Test
    void removesALink() {
        SiteRepoLinkRepository links = open();
        links.save("example.com", "octocat/website");
        links.save("blog.example.com", "octocat/blog");

        assertThat(links.remove("example.com")).isTrue();

        assertThat(links.find("example.com")).isEmpty();
        assertThat(links.findAll()).containsExactly(Map.entry("blog.example.com", "octocat/blog"));
    }

    @Test
    void removingASiteWithoutALinkDoesNothing() {
        SiteRepoLinkRepository links = open();

        assertThat(links.remove("example.com")).isFalse();

        assertThat(dir.resolve("settings.json")).doesNotExist();
    }

    @Test
    void linksSurviveARestart() {
        SiteRepoLinkRepository beforeRestart = open();
        beforeRestart.save("example.com", "octocat/website");
        beforeRestart.save("blog.example.com", "octocat/blog");
        beforeRestart.save("example.com", "octocat/new-website");
        beforeRestart.remove("blog.example.com");

        SiteRepoLinkRepository afterRestart = open();

        assertThat(afterRestart.findAll()).containsExactly(Map.entry("example.com", "octocat/new-website"));
    }

    @Test
    void writesTheDocumentedFileFormat() throws Exception {
        open().save("example.com", "octocat/website");

        String file = Files.readString(dir.resolve("settings.json"));

        assertThat(new ObjectMapper().readTree(file).toString())
                .isEqualTo("{\"version\":1,\"siteRepoLinks\":{\"example.com\":\"octocat/website\"}}");
    }

    @Test
    void domainsAreComparedWithoutCaseOrSurroundingSpaces() {
        SiteRepoLinkRepository links = open();

        links.save("  Example.COM ", "Octocat/Website");

        assertThat(links.find("example.com")).contains("Octocat/Website");
        assertThat(links.find("EXAMPLE.com")).contains("Octocat/Website");
        assertThat(links.findAll()).containsOnlyKeys("example.com");
        assertThat(links.remove("EXAMPLE.COM")).isTrue();
        assertThat(links.findAll()).isEmpty();
    }

    @Test
    void rejectsABlankDomainOrARepoThatIsNotOwnerSlashName() {
        SiteRepoLinkRepository links = open();

        for (String domain : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> links.save(domain, "octocat/website")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> links.find(domain)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> links.remove(domain)).isInstanceOf(IllegalArgumentException.class);
        }
        for (String repo : new String[] {null, "", "website", "octocat/", "/website", "a/b/c", "octo cat/website"}) {
            assertThatThrownBy(() -> links.save("example.com", repo)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(links.findAll()).isEmpty();
        assertThat(dir.resolve("settings.json")).doesNotExist();
    }

    @Test
    void entriesInTheFileThatAreNotALinkAreIgnored() throws Exception {
        Files.writeString(dir.resolve("settings.json"), """
                {"version":1,"siteRepoLinks":{"example.com":"octocat/website","bad.com":42,"worse.com":"no-slash"}}
                """);

        SiteRepoLinkRepository links = open();

        assertThat(links.findAll()).containsExactly(Map.entry("example.com", "octocat/website"));
        assertThat(links.find("bad.com")).isEmpty();
        assertThat(problemLog.recent(null, null)).isEmpty();
    }

    @Test
    void aCorruptFileMeansNoLinksAndSavingWorksAgain() throws Exception {
        Files.writeString(dir.resolve("settings.json"), "{\"version\":1,\"siteRepoLinks\":{\"example.com\":\"octo");

        SiteRepoLinkRepository links = open();

        assertThat(links.findAll()).isEmpty();
        assertThat(problemLog.recent(null, null)).hasSize(1);

        links.save("example.com", "octocat/website");
        assertThat(open().find("example.com")).contains("octocat/website");
    }

    @Test
    void whenSavingFailsTheLinksStayAsTheyWere() throws Exception {
        SiteRepoLinkRepository links = open();
        links.save("example.com", "octocat/website");
        Files.createDirectory(dir.resolve("settings.json.tmp"));

        assertThatThrownBy(() -> links.save("example.com", "octocat/other"))
                .isInstanceOf(SettingsUnavailableException.class);
        assertThatThrownBy(() -> links.remove("example.com")).isInstanceOf(SettingsUnavailableException.class);

        assertThat(links.find("example.com")).contains("octocat/website");
        assertThat(open().find("example.com")).contains("octocat/website");
    }

    @Test
    void savingFromManyThreadsAtOnceLosesNothing() throws Exception {
        SiteRepoLinkRepository links = open();
        int threads = 8;
        int perThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> running = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int thread = t;
                running.add(pool.submit(() -> {
                    for (int i = 0; i < perThread; i++) {
                        links.save("site-" + thread + "-" + i + ".example.com", "octocat/repo-" + thread + "-" + i);
                        links.find("site-0-0.example.com");
                        links.findAll();
                    }
                }));
            }
            for (Future<?> future : running) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(links.findAll()).hasSize(threads * perThread);
        // The file on disk is complete and valid too.
        Map<String, String> afterRestart = open().findAll();
        assertThat(afterRestart).hasSize(threads * perThread)
                .containsEntry("site-7-24.example.com", "octocat/repo-7-24");
        assertThat(problemLog.recent(null, null)).isEmpty();
    }

    /** A new repository over the same file: what the app does when it starts. */
    private SiteRepoLinkRepository open() {
        return new JsonFileSiteRepoLinkRepository(
                new SettingsFile(dir.resolve("settings.json"), 1, List.of(), problemLog, Clock.systemUTC()));
    }
}
