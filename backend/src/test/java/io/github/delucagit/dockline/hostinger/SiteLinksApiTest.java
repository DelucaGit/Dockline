package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.delucagit.dockline.ClockAdvancingWaiter;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.github.GithubStubs;
import io.github.delucagit.dockline.settings.SiteRepoLinkRepository;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The site–repo link endpoints ({@code /api/sites/...}) over real HTTP, with Hostinger and GitHub faked by
 * WireMock, time driven by a {@link MutableClock} and the settings file in a folder of its own. Every response
 * and every log line is collected and checked for the tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SiteLinksApiTest {

    private static final String TOKEN = "hst_FakeSiteLinksToken0123456789abcdefXYZ";
    private static final String GITHUB_TOKEN = "gho_FakeSiteLinksToken0123456789abcdefgh";
    private static final String USER = "u100";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer HOSTINGER = HostingerStubs.start();
    private static final WireMockServer GITHUB = GithubStubs.start();
    private static final String GIT_PATHS = "/api/hosting/v1/accounts/.*/git/.*";

    @DynamicPropertySource
    static void pointAtFakes(DynamicPropertyRegistry registry) {
        registry.add("dockline.hostinger.api-url", HOSTINGER::baseUrl);
        registry.add("dockline.github.api-url", GITHUB::baseUrl);
        registry.add("dockline.github.oauth-url", GITHUB::baseUrl);
        registry.add("dockline.settings.dir",
                () -> Path.of("target", "test-settings", "site-links-" + UUID.randomUUID()).toAbsolutePath().toString());
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        ClockAdvancingWaiter testWaiter(MutableClock clock) {
            return new ClockAdvancingWaiter(clock);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    MutableClock clock;

    @Autowired
    TokenStore tokenStore;

    @Autowired
    SiteRepoLinkRepository manualLinks;

    private final List<String> responses = new ArrayList<>();

    @BeforeEach
    void cleanStart() throws Exception {
        // A minute later: the Hostinger request cap from earlier tests is free again.
        clock.advanceSeconds(61);
        HOSTINGER.resetAll();
        GITHUB.resetAll();
        // Removing the token also forgets every saved Hostinger answer.
        send("DELETE", "/api/hostinger/token", null);
        send("POST", "/api/github/disconnect", null);
        send("DELETE", "/api/log", null);
        for (String domain : manualLinks.findAll().keySet()) {
            manualLinks.remove(domain);
        }
        responses.clear();
        tokenStore.save(Source.HOSTINGER, TOKEN);
        tokenStore.save(Source.GITHUB, GITHUB_TOKEN);
    }

    @AfterEach
    void theTokensNeverLeaveTheBackend(CapturedOutput output) throws Exception {
        responses.add(send("GET", "/api/log", null).body());
        for (String text : responses) {
            assertThat(text).doesNotContain(TOKEN).doesNotContain(GITHUB_TOKEN);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(GITHUB_TOKEN);
        send("DELETE", "/api/hostinger/token", null);
        send("POST", "/api/github/disconnect", null);
    }

    @AfterAll
    static void stopFakeServers() {
        HOSTINGER.stop();
        GITHUB.stop();
    }

    @Test
    void anAutoDeployedSiteShowsItsRepoFromHostingerAndHidesASavedManualLink() throws Exception {
        sites("Shop.example");
        HostingerStubs.gitAutoDeployment(HOSTINGER, USER, "Shop.example", "octocat", "shop");
        HostingerStubs.gitInstallations(HOSTINGER, "github");
        GithubStubs.repository(GITHUB, "octocat", "shop");
        manualLinks.save("shop.example", "octocat/old-manual");

        HttpResponse<String> response = send("GET", "/api/sites/SHOP.example", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode site = JSON.readTree(response.body());
        assertThat(site.properties()).hasSize(6);
        assertThat(site.get("domain").asString()).isEqualTo("shop.example");
        assertThat(site.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(site.get("hostingerChecked").asBoolean()).isTrue();
        assertThat(site.get("repo").get("fullName").asString()).isEqualTo("octocat/shop");
        assertThat(site.get("repo").get("htmlUrl").asString()).isEqualTo("https://github.com/octocat/shop");
        assertThat(site.get("repoWarning").isNull()).isTrue();
        assertThat(site.get("problems")).isEmpty();
        // The manual link is hidden, not deleted.
        assertThat(manualLinks.find("shop.example")).hasValue("octocat/old-manual");
        // The auto-deployment answered, so the Git section was not asked.
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.gitAutoDeploymentPath(USER, "Shop.example")))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        HOSTINGER.verify(0, getRequestedFor(urlPathEqualTo(HostingerStubs.gitRepositoriesPath(USER, "Shop.example"))));
        GITHUB.verify(1, getRequestedFor(urlPathEqualTo("/repos/octocat/shop"))
                .withHeader("Authorization", equalTo("Bearer " + GITHUB_TOKEN)));
    }

    @Test
    void aRepositoryClonedInTheGitSectionCountsAsGitConnected() throws Exception {
        sites("blog.example");
        HostingerStubs.gitNoAutoDeployment(HOSTINGER, USER, "blog.example");
        HostingerStubs.gitRepositories(HOSTINGER, USER, "blog.example",
                "https://gitlab.com/someone/else.git", "https://***@github.com/Octo-Org/blog.site.git");
        GithubStubs.repository(GITHUB, "Octo-Org", "blog.site");

        JsonNode site = JSON.readTree(send("GET", "/api/sites/blog.example", null).body());

        assertThat(site.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(site.get("repo").get("fullName").asString()).isEqualTo("Octo-Org/blog.site");
        HOSTINGER.verify(0, getRequestedFor(urlPathEqualTo(HostingerStubs.GIT_INSTALLATIONS_PATH)));
    }

    @Test
    void aSiteConnectedToAnotherGitHostIsNotGitConnected() throws Exception {
        sites("lab.example", "hub.example");
        HostingerStubs.gitAutoDeployment(HOSTINGER, USER, "lab.example", "someone", "lab");
        HostingerStubs.gitInstallations(HOSTINGER, "gitlab");
        HostingerStubs.gitRepositories(HOSTINGER, USER, "lab.example", "git@gitlab.com:someone/lab.git");
        HostingerStubs.gitNoAutoDeployment(HOSTINGER, USER, "hub.example");
        HostingerStubs.gitRepositories(HOSTINGER, USER, "hub.example", "git@bitbucket.org:someone/hub.git");

        JsonNode lab = JSON.readTree(send("GET", "/api/sites/lab.example", null).body());
        JsonNode hub = JSON.readTree(send("GET", "/api/sites/hub.example", null).body());

        for (JsonNode site : List.of(lab, hub)) {
            assertThat(site.get("source").asString()).isEqualTo("NONE");
            assertThat(site.get("repo").isNull()).isTrue();
            assertThat(site.get("hostingerChecked").asBoolean()).isTrue();
            assertThat(site.get("problems")).isEmpty();
        }
        assertThat(GITHUB.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void aManualLinkCanBeSetChangedAndRemoved() throws Exception {
        sites("plain.example");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "plain.example");
        GithubStubs.repository(GITHUB, "octocat", "first");
        GithubStubs.repository(GITHUB, "octocat", "second");

        JsonNode none = JSON.readTree(send("GET", "/api/sites/plain.example", null).body());
        assertThat(none.get("source").asString()).isEqualTo("NONE");
        assertThat(none.get("repo").isNull()).isTrue();
        assertThat(none.get("repoWarning").isNull()).isTrue();

        HttpResponse<String> set = send("PUT", "/api/sites/Plain.Example/repo", "{\"repoFullName\":\" octocat/first \"}");
        assertThat(set.statusCode()).isEqualTo(200);
        JsonNode linked = JSON.readTree(set.body());
        assertThat(linked.get("domain").asString()).isEqualTo("plain.example");
        assertThat(linked.get("source").asString()).isEqualTo("MANUAL");
        assertThat(linked.get("repo").get("fullName").asString()).isEqualTo("octocat/first");
        assertThat(linked.get("repo").get("htmlUrl").asString()).isEqualTo("https://github.com/octocat/first");
        assertThat(manualLinks.find("plain.example")).hasValue("octocat/first");

        JsonNode changed = JSON.readTree(send("PUT", "/api/sites/plain.example/repo",
                "{\"repoFullName\":\"octocat/second\"}").body());
        assertThat(changed.get("repo").get("fullName").asString()).isEqualTo("octocat/second");
        assertThat(JSON.readTree(send("GET", "/api/sites/plain.example", null).body()).get("source").asString())
                .isEqualTo("MANUAL");

        assertThat(send("DELETE", "/api/sites/plain.example/repo", null).statusCode()).isEqualTo(204);
        assertThat(manualLinks.find("plain.example")).isEmpty();
        assertThat(JSON.readTree(send("GET", "/api/sites/plain.example", null).body()).get("source").asString())
                .isEqualTo("NONE");
        // Removing again is fine.
        assertThat(send("DELETE", "/api/sites/plain.example/repo", null).statusCode()).isEqualTo(204);
    }

    @Test
    void aManualLinkOnAGitConnectedSiteIsRefused() throws Exception {
        sites("shop.example");
        HostingerStubs.gitNoAutoDeployment(HOSTINGER, USER, "shop.example");
        HostingerStubs.gitRepositories(HOSTINGER, USER, "shop.example", "git@github.com:octocat/shop.git");

        HttpResponse<String> response = send("PUT", "/api/sites/shop.example/repo", "{\"repoFullName\":\"octocat/other\"}");

        assertProblem(response, 409, SiteLinks.GIT_CONNECTED);
        assertThat(JSON.readTree(response.body()).get("detail").asString()).contains("shop.example", "octocat/shop",
                "manual link cannot be set");
        assertThat(manualLinks.findAll()).isEmpty();
    }

    @Test
    void aBadRepositoryNameOrUnknownSiteIsRefused() throws Exception {
        sites("plain.example");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "plain.example");

        for (String body : List.of("{}", "{\"repoFullName\":\"no-slash\"}", "{\"repoFullName\":\"a/b/c\"}",
                "{\"repoFullName\":\"https://github.com/octocat/x\"}")) {
            assertProblem(send("PUT", "/api/sites/plain.example/repo", body), 400, SiteLinks.INVALID_REPOSITORY);
        }
        assertProblem(send("PUT", "/api/sites/other.example/repo", "{\"repoFullName\":\"octocat/x\"}"), 404,
                SiteLinks.SITE_NOT_FOUND);
        assertProblem(send("GET", "/api/sites/other.example", null), 404, SiteLinks.SITE_NOT_FOUND);
        assertThat(manualLinks.findAll()).isEmpty();
    }

    @Test
    void aMissingOrInaccessibleRepositoryIsAWarningNotAnError() throws Exception {
        sites("plain.example");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "plain.example");
        manualLinks.save("plain.example", "octocat/gone");
        GithubStubs.repositoryNotFound(GITHUB, "octocat", "gone");

        HttpResponse<String> response = send("GET", "/api/sites/plain.example", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode site = JSON.readTree(response.body());
        assertThat(site.get("source").asString()).isEqualTo("MANUAL");
        assertThat(site.get("repo").get("fullName").asString()).isEqualTo("octocat/gone");
        assertThat(site.get("repo").get("htmlUrl").asString()).isEqualTo("https://github.com/octocat/gone");
        assertThat(site.get("repoWarning").get("code").asString()).isEqualTo("REPO_NOT_FOUND");
        assertThat(site.get("repoWarning").get("message").asString()).contains("octocat/gone");
        assertThat(site.get("problems")).isEmpty();

        GithubStubs.repositoryError(GITHUB, "octocat", "gone", 403, "Repository access blocked");
        JsonNode blocked = JSON.readTree(send("GET", "/api/sites/plain.example", null).body());
        assertThat(blocked.get("repoWarning").get("code").asString()).isEqualTo("REPO_NOT_ACCESSIBLE");

        GithubStubs.repositoryError(GITHUB, "octocat", "gone", 301, "Moved Permanently");
        JsonNode moved = JSON.readTree(send("GET", "/api/sites/plain.example", null).body());
        assertThat(moved.get("repoWarning").get("code").asString()).isEqualTo("REPO_MOVED");
        // A warning is not a problem: nothing is logged.
        assertThat(JSON.readTree(send("GET", "/api/log", null).body())).isEmpty();
    }

    @Test
    void aGithubFailureIsReportedPerSourceAndTheLinkIsStillShown() throws Exception {
        sites("shop.example");
        HostingerStubs.gitAutoDeployment(HOSTINGER, USER, "shop.example", "octocat", "shop");

        GithubStubs.repositoryError(GITHUB, "octocat", "shop", 503, "Service Unavailable");
        JsonNode down = JSON.readTree(send("GET", "/api/sites/shop.example", null).body());
        assertThat(down.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(down.get("repo").get("htmlUrl").asString()).isEqualTo("https://github.com/octocat/shop");
        assertThat(down.get("repoWarning").isNull()).isTrue();
        assertThat(down.get("problems")).hasSize(1);
        assertThat(down.get("problems").get(0).get("source").asString()).isEqualTo("GITHUB");
        assertThat(down.get("problems").get(0).get("code").asString()).isEqualTo("GITHUB_UNAVAILABLE");
        assertThat(down.get("problems").get(0).get("message").asString()).isNotBlank();
        assertThat(JSON.readTree(send("GET", "/api/log?source=GITHUB", null).body())).hasSize(1);

        GithubStubs.repositoryError(GITHUB, "octocat", "shop", 401, "Bad credentials");
        JsonNode rejected = JSON.readTree(send("GET", "/api/sites/shop.example", null).body());
        assertThat(rejected.get("problems").get(0).get("code").asString()).isEqualTo("GITHUB_TOKEN_REJECTED");

        send("POST", "/api/github/disconnect", null);
        JsonNode notConnected = JSON.readTree(send("GET", "/api/sites/shop.example", null).body());
        assertThat(notConnected.get("repo").get("fullName").asString()).isEqualTo("octocat/shop");
        assertThat(notConnected.get("problems").get(0).get("code").asString()).isEqualTo("GITHUB_NOT_CONNECTED");
        assertThat(notConnected.get("problems").get(0).get("retryAfterSeconds").isNull()).isTrue();
    }

    @Test
    void aHostingerFailureIsReportedPerSourceAndTheManualLinkIsStillShown() throws Exception {
        sites("plain.example");
        HostingerStubs.gitServerError(HOSTINGER, USER, "plain.example");
        GithubStubs.repository(GITHUB, "octocat", "plain");
        manualLinks.save("plain.example", "octocat/plain");

        HttpResponse<String> response = send("GET", "/api/sites/plain.example", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode site = JSON.readTree(response.body());
        assertThat(site.get("source").asString()).isEqualTo("MANUAL");
        assertThat(site.get("hostingerChecked").asBoolean()).isFalse();
        assertThat(site.get("repo").get("fullName").asString()).isEqualTo("octocat/plain");
        assertThat(site.get("repoWarning").isNull()).isTrue();
        assertThat(site.get("problems")).hasSize(1);
        assertThat(site.get("problems").get(0).get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(site.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.UNAVAILABLE);
        assertThat(hostinger()).isEqualTo("LIMITED: Hostinger answered with an error (HTTP 500). Try again later.");
        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("action").asString())
                .isEqualTo("GET " + HostingerStubs.gitAutoDeploymentPath(USER, "plain.example"));
        assertThat(entries.get(0).get("statusCode").asInt()).isEqualTo(500);

        // Setting a link needs Hostinger (is the site Git-connected?); removing one does not.
        assertProblem(send("PUT", "/api/sites/plain.example/repo", "{\"repoFullName\":\"octocat/other\"}"), 502,
                HostingerSiteList.UNAVAILABLE);
        assertThat(manualLinks.find("plain.example")).hasValue("octocat/plain");
        assertThat(send("DELETE", "/api/sites/plain.example/repo", null).statusCode()).isEqualTo(204);
        assertThat(manualLinks.find("plain.example")).isEmpty();
    }

    @Test
    void aRejectedTokenAndARateLimitOnTheGitEndpointsUseTheHostingerCodes() throws Exception {
        sites("plain.example");
        HostingerStubs.gitUnauthorized(HOSTINGER, USER, "plain.example", TOKEN);

        JsonNode rejected = JSON.readTree(send("GET", "/api/sites/plain.example", null).body());
        assertThat(rejected.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.TOKEN_REJECTED);
        assertThat(hostinger()).isEqualTo("ERROR: " + HostingerStatusProvider.REVOKED);

        HostingerStubs.gitRateLimited(HOSTINGER, USER, "plain.example", 42);
        JsonNode limited = JSON.readTree(send("GET", "/api/sites/plain.example", null).body());
        assertThat(limited.get("source").asString()).isEqualTo("NONE");
        assertThat(limited.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.RATE_LIMITED);
        assertThat(limited.get("problems").get(0).get("retryAfterSeconds").asLong()).isEqualTo(42);
        HttpResponse<String> put = send("PUT", "/api/sites/plain.example/repo", "{\"repoFullName\":\"octocat/x\"}");
        assertProblem(put, 429, HostingerSiteList.RATE_LIMITED);
        assertThat(put.headers().firstValue("Retry-After")).hasValue("42");
    }

    @Test
    void withoutAHostingerTokenTheManualLinksAreStillReachable() throws Exception {
        send("DELETE", "/api/hostinger/token", null);
        GithubStubs.repository(GITHUB, "octocat", "plain");
        manualLinks.save("plain.example", "octocat/plain");

        JsonNode site = JSON.readTree(send("GET", "/api/sites/plain.example", null).body());
        assertThat(site.get("source").asString()).isEqualTo("MANUAL");
        assertThat(site.get("hostingerChecked").asBoolean()).isFalse();
        assertThat(site.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.NOT_CONNECTED);

        JsonNode links = JSON.readTree(send("GET", "/api/sites/links", null).body());
        assertThat(links.get("complete").asBoolean()).isFalse();
        assertThat(links.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.NOT_CONNECTED);
        assertThat(links.get("links")).hasSize(1);
        assertThat(links.get("links").get(0).get("domain").asString()).isEqualTo("plain.example");
        assertThat(links.get("links").get(0).get("source").asString()).isEqualTo("MANUAL");
        assertThat(links.get("links").get(0).get("hostingerChecked").asBoolean()).isFalse();

        assertProblem(send("PUT", "/api/sites/plain.example/repo", "{\"repoFullName\":\"octocat/x\"}"), 409,
                HostingerSiteList.NOT_CONNECTED);
        assertThat(HOSTINGER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theLinksOfAllSitesComeFromHostingerAndTheManualLinksAndAreReusedForFiveMinutes() throws Exception {
        HostingerStubs.websites(HOSTINGER, List.of(List.of(
                HostingerStubs.site("auto.example", USER, 1, true, "2024-01-15T10:30:00+00:00"),
                HostingerStubs.site("cloned.example", USER, 1, true, "2024-01-15T10:30:00+00:00"),
                HostingerStubs.site("Manual.example", USER, 1, true, "2024-01-15T10:30:00+00:00"),
                HostingerStubs.site("none.example", USER, 1, true, "2024-01-15T10:30:00+00:00"),
                "{\"domain\":\"nouser.example\",\"is_enabled\":true,\"username\":null,\"order_id\":3}",
                "{\"domain\":null,\"is_enabled\":true,\"username\":null,\"order_id\":3}")));
        HostingerStubs.gitAutoDeployment(HOSTINGER, USER, "auto.example", "octocat", "auto");
        HostingerStubs.gitInstallations(HOSTINGER, "github");
        HostingerStubs.gitNoAutoDeployment(HOSTINGER, USER, "cloned.example");
        HostingerStubs.gitRepositories(HOSTINGER, USER, "cloned.example", "ssh://git@github.com:22/octocat/cloned");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "Manual.example");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "none.example");
        manualLinks.save("manual.example", "octocat/by-hand");
        manualLinks.save("auto.example", "octocat/hidden");

        HttpResponse<String> response = send("GET", "/api/sites/links", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(3);
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("problems")).isEmpty();
        assertThat(body.get("links").valueStream().map(link -> link.get("domain").asString() + " "
                + link.get("source").asString() + " "
                + (link.get("repo").isNull() ? null : link.get("repo").get("fullName").asString())))
                .containsExactly("auto.example HOSTINGER octocat/auto", "cloned.example HOSTINGER octocat/cloned",
                        "manual.example MANUAL octocat/by-hand", "none.example NONE null", "nouser.example NONE null");
        JsonNode auto = body.get("links").get(0);
        assertThat(auto.properties()).hasSize(4);
        assertThat(auto.get("repo").get("htmlUrl").asString()).isEqualTo("https://github.com/octocat/auto");
        assertThat(auto.get("hostingerChecked").asBoolean()).isTrue();
        // 1 (auto) + 1 installations + 2 + 2 + 2; the website without a username is not asked about.
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching("/api/hosting/v1/(accounts|git)/.*")))).hasSize(8);
        assertThat(GITHUB.findAll(anyRequestedFor(anyUrl()))).isEmpty();

        // Within five minutes nothing about Git is asked again (the website list itself is re-read after 60 s).
        clock.advanceSeconds(200);
        JsonNode again = JSON.readTree(send("GET", "/api/sites/links", null).body());
        assertThat(again.get("links")).isEqualTo(body.get("links"));
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching("/api/hosting/v1/(accounts|git)/.*")))).hasSize(8);

        // The detail view can ask again about one site.
        HostingerStubs.gitRepositories(HOSTINGER, USER, "none.example", "https://github.com/octocat/new.git");
        GithubStubs.repository(GITHUB, "octocat", "new");
        assertThat(JSON.readTree(send("GET", "/api/sites/none.example", null).body()).get("source").asString())
                .isEqualTo("NONE");
        assertThat(JSON.readTree(send("GET", "/api/sites/none.example?refresh=true", null).body()).get("source")
                .asString()).isEqualTo("HOSTINGER");
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching("/api/hosting/v1/(accounts|git)/.*")))).hasSize(10);
    }

    @Test
    void aWebsiteWithoutGitIsNotAskedAboutAgainForFiveMinutes() throws Exception {
        sites("nogit.example");
        HostingerStubs.gitNotAvailable(HOSTINGER, USER, "nogit.example");

        JsonNode first = JSON.readTree(send("GET", "/api/sites/links", null).body());
        assertThat(first.get("links").get(0).get("source").asString()).isEqualTo("NONE");
        assertThat(first.get("links").get(0).get("hostingerChecked").asBoolean()).isTrue();
        assertThat(first.get("complete").asBoolean()).isTrue();
        int asked = HOSTINGER.findAll(getRequestedFor(urlPathMatching(GIT_PATHS))).size();
        assertThat(asked).isEqualTo(2);

        clock.advanceSeconds(200);
        JsonNode again = JSON.readTree(send("GET", "/api/sites/links", null).body());
        assertThat(again.get("links")).isEqualTo(first.get("links"));
        assertThat(JSON.readTree(send("GET", "/api/sites/nogit.example", null).body()).get("source").asString())
                .isEqualTo("NONE");
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(GIT_PATHS)))).hasSize(asked);
    }

    @Test
    void theLinksListNeverSendsMoreThanItsShareOfTheRequestCap() throws Exception {
        List<String> domains = IntStream.rangeClosed(1, 30).mapToObj(i -> "site-%02d.example".formatted(i)).toList();
        sites(domains.toArray(String[]::new));
        domains.forEach(domain -> HostingerStubs.gitNotConnected(HOSTINGER, USER, domain));

        JsonNode first = JSON.readTree(send("GET", "/api/sites/links", null).body());

        // 2 requests per site: 20 sites fit into 40 requests; the other 10 wait for the next call.
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(GIT_PATHS)))).hasSize(SiteLinks.MAX_REQUESTS_PER_LIST);
        assertThat(first.get("complete").asBoolean()).isFalse();
        assertThat(first.get("problems")).isEmpty();
        assertThat(first.get("links")).hasSize(30);
        assertThat(first.get("links").valueStream().filter(link -> link.get("hostingerChecked").asBoolean())).hasSize(20);

        clock.advanceSeconds(61);
        JsonNode second = JSON.readTree(send("GET", "/api/sites/links", null).body());

        assertThat(second.get("complete").asBoolean()).isTrue();
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(GIT_PATHS)))).hasSize(60);
    }

    @Test
    void aHostingerFailureWhileListingStopsAskingAndKeepsTheRest() throws Exception {
        sites("a.example", "b.example", "c.example");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "a.example");
        HostingerStubs.gitServerError(HOSTINGER, USER, "b.example");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "c.example");
        manualLinks.save("c.example", "octocat/c");

        JsonNode body = JSON.readTree(send("GET", "/api/sites/links", null).body());

        assertThat(body.get("complete").asBoolean()).isFalse();
        assertThat(body.get("problems")).hasSize(1);
        assertThat(body.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.UNAVAILABLE);
        assertThat(body.get("links").valueStream().map(link -> link.get("domain").asString() + " "
                + link.get("source").asString() + " " + link.get("hostingerChecked").asBoolean()))
                .containsExactly("a.example NONE true", "b.example NONE false", "c.example MANUAL false");
        // a: 2 requests, b: 1 failed request, c: not asked.
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(GIT_PATHS)))).hasSize(3);
    }

    @Test
    void theEndpointsAreInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs", null).body();

        assertThat(docs).contains("\"/api/sites/links\"", "\"/api/sites/{domain}\"", "\"/api/sites/{domain}/repo\"",
                "\"listSiteLinks\"", "\"getSite\"", "\"linkSiteRepo\"", "\"unlinkSiteRepo\"", "\"SiteDetail\"",
                "\"SiteLinks\"", "\"SiteLinkSource\"", "\"SiteRepoWarning\"", "\"SiteSourceProblem\"",
                "SITE_GIT_CONNECTED", "SITE_NOT_FOUND", "INVALID_REPOSITORY", "REPO_NOT_FOUND");
    }

    /** The website list: these domains, all in the hosting account {@link #USER}. */
    private static void sites(String... domains) {
        HostingerStubs.websites(HOSTINGER, List.of(java.util.Arrays.stream(domains)
                .map(domain -> HostingerStubs.site(domain, USER, 111, true, "2024-01-15T10:30:00+00:00")).toList()));
    }

    private void assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("code").asString()).isEqualTo(code);
        assertThat(problem.get("status").asInt()).isEqualTo(status);
        assertThat(problem.get("title").asString()).isNotBlank();
        assertThat(problem.get("detail").asString()).isNotBlank();
    }

    /** "STATE: message" of HOSTINGER in GET /api/status. */
    private String hostinger() throws Exception {
        JsonNode hostinger = JSON.readTree(send("GET", "/api/status", null).body()).get("sources").get(1);
        assertThat(hostinger.get("source").asString()).isEqualTo("HOSTINGER");
        JsonNode message = hostinger.get("message");
        return hostinger.get("state").asString() + ": " + (message == null || message.isNull() ? null : message.asString());
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.method(method, HttpRequest.BodyPublishers.ofString(json)).header("Content-Type", "application/json");
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        responses.add(response.body());
        return response;
    }
}
