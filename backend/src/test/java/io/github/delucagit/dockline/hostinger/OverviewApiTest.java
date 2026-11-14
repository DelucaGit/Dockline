package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.delucagit.dockline.ClockAdvancingWaiter;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.github.GithubRepositoryList;
import io.github.delucagit.dockline.github.GithubStubs;
import io.github.delucagit.dockline.settings.SiteRepoLinkRepository;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * The overview endpoint ({@code GET /api/overview}) over real HTTP, with Hostinger and GitHub faked by WireMock,
 * time driven by a {@link MutableClock} and the settings file in a folder of its own. Every response and every
 * log line is collected and checked for the tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class OverviewApiTest {

    private static final String TOKEN = "hst_FakeOverviewToken0123456789abcdefXYZW";
    private static final String GITHUB_TOKEN = "gho_FakeOverviewToken0123456789abcdefghi";
    private static final String USER = "u100";
    private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer HOSTINGER = HostingerStubs.start();
    private static final WireMockServer GITHUB = GithubStubs.start();
    private static final String BUILDS_PATHS = "/api/hosting/v1/accounts/.*/nodejs/builds";

    @DynamicPropertySource
    static void pointAtFakes(DynamicPropertyRegistry registry) {
        registry.add("dockline.hostinger.api-url", HOSTINGER::baseUrl);
        registry.add("dockline.github.api-url", GITHUB::baseUrl);
        registry.add("dockline.github.oauth-url", GITHUB::baseUrl);
        registry.add("dockline.settings.dir",
                () -> Path.of("target", "test-settings", "overview-" + UUID.randomUUID()).toAbsolutePath().toString());
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
        // Ten minutes later: the Hostinger request cap, any Retry-After block and every saved answer (also the
        // five-minute Git connections) from earlier tests are over.
        clock.advanceSeconds(600);
        HOSTINGER.resetAll();
        GITHUB.resetAll();
        // Removing the tokens also forgets every saved Hostinger answer and the cached statuses.
        send("DELETE", "/api/hostinger/token", null);
        send("POST", "/api/github/disconnect", null);
        send("DELETE", "/api/log", null);
        for (String domain : manualLinks.findAll().keySet()) {
            manualLinks.remove(domain);
        }
        responses.clear();
        tokenStore.save(Source.HOSTINGER, TOKEN);
        tokenStore.save(Source.GITHUB, GITHUB_TOKEN);
        HostingerStubs.subscriptionsOk(HOSTINGER);
        GithubStubs.user(GITHUB, "octocat");
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

    /**
     * Three sites: app.example (Node.js, latest deploy failed, not Git-connected), ok.example (Node.js, deployed,
     * auto-deploys octocat/ok) and blog.example (WordPress, manually linked to octocat/gone, which GitHub does not
     * list). Two repositories. Four domains: one expired by date, one expired by status, one expiring in ten days,
     * one in a year.
     */
    private void everythingWorks() throws Exception {
        sites(site("app.example", "nodejs"), site("ok.example", "nodejs"), site("blog.example", "wordpress"));
        HostingerStubs.buildFailed(HOSTINGER, USER, "app.example", SHA, "Break the build");
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "ok.example", SHA, "Initial commit");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "app.example");
        HostingerStubs.gitAutoDeployment(HOSTINGER, USER, "ok.example", "octocat", "ok");
        HostingerStubs.gitInstallations(HOSTINGER, "github");
        HostingerStubs.gitNotConnected(HOSTINGER, USER, "blog.example");
        manualLinks.save("blog.example", "octocat/gone");
        GithubStubs.repos(GITHUB, List.of(List.of(
                GithubStubs.repo("octocat", "ok", false, "2026-01-01T00:00:00Z"),
                GithubStubs.repo("octocat", "shop", true, null))));
        domains();
    }

    private void domains() {
        Instant now = clock.instant();
        HostingerStubs.domains(HOSTINGER, List.of(
                HostingerStubs.domain(1, "old.example", "active", now.minus(Duration.ofDays(2)).toString()),
                HostingerStubs.domain(2, "gone.example", "expired", now.plus(Duration.ofDays(300)).toString()),
                HostingerStubs.domain(3, "soon.example", "active", now.plus(Duration.ofDays(10)).toString()),
                HostingerStubs.domain(4, "fine.example", "active", now.plus(Duration.ofDays(365)).toString())));
    }

    @Test
    void countsProblemsLatestDeploysAndSourcesInOneAnswer() throws Exception {
        everythingWorks();
        Instant now = clock.instant();

        HttpResponse<String> response = send("GET", "/api/overview", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(6);
        assertThat(body.get("sites").get("count").asInt()).isEqualTo(3);
        assertThat(body.get("sites").get("errors")).isEmpty();
        assertThat(body.get("repos").get("count").asInt()).isEqualTo(2);
        assertThat(body.get("repos").get("errors")).isEmpty();
        // The oldest data used is the deploys' builds, all fetched now; nothing older is saved.
        assertThat(Instant.parse(body.get("fetchedAt").asString())).isEqualTo(now);

        JsonNode sources = body.get("sources");
        assertThat(sources).hasSize(2);
        assertThat(sources.get(0).get("source").asString()).isEqualTo("GITHUB");
        assertThat(sources.get(0).get("state").asString()).isEqualTo("CONNECTED");
        assertThat(sources.get(1).get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(sources.get(1).get("state").asString()).isEqualTo("CONNECTED");

        JsonNode deploys = body.get("latestDeploys");
        assertThat(deploys.get("errors")).isEmpty();
        assertThat(deploys.get("deploys")).hasSize(2);
        JsonNode newest = deploys.get("deploys").get(0);
        assertThat(newest.properties()).hasSize(4);
        assertThat(newest.get("domain").asString()).isEqualTo("app.example");
        assertThat(newest.get("status").asString()).isEqualTo("FAILED");
        assertThat(newest.get("at").asString()).isEqualTo("2024-05-29T06:10:41Z");
        assertThat(newest.get("commit").get("sha").asString()).isEqualTo(SHA);
        assertThat(deploys.get("deploys").get(1).get("domain").asString()).isEqualTo("ok.example");
        assertThat(deploys.get("deploys").get(1).get("status").asString()).isEqualTo("SUCCEEDED");

        JsonNode problems = body.get("problems");
        assertThat(problems.get("errors")).isEmpty();
        JsonNode items = problems.get("items");
        assertThat(kinds(items)).containsExactly("DEPLOY_FAILED", "DOMAIN_EXPIRED", "DOMAIN_EXPIRED",
                "DOMAIN_EXPIRING", "LINK_BROKEN");
        JsonNode failed = items.get(0);
        assertThat(failed.properties()).hasSize(6);
        assertThat(failed.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(failed.get("domain").asString()).isEqualTo("app.example");
        assertThat(failed.get("repoFullName").isNull()).isTrue();
        assertThat(failed.get("at").asString()).isEqualTo("2024-05-29T06:10:41Z");
        assertThat(failed.get("message").asString()).isNotBlank();
        // Expired, earliest expiry first: by date two days ago, then by Hostinger's status.
        assertThat(items.get(1).get("domain").asString()).isEqualTo("old.example");
        assertThat(items.get(2).get("domain").asString()).isEqualTo("gone.example");
        assertThat(items.get(3).get("domain").asString()).isEqualTo("soon.example");
        assertThat(Instant.parse(items.get(3).get("at").asString())).isEqualTo(now.plus(Duration.ofDays(10)));
        JsonNode broken = items.get(4);
        assertThat(broken.get("source").asString()).isEqualTo("GITHUB");
        assertThat(broken.get("domain").asString()).isEqualTo("blog.example");
        assertThat(broken.get("repoFullName").asString()).isEqualTo("octocat/gone");
        assertThat(broken.get("at").isNull()).isTrue();

        assertThat(JSON.readTree(send("GET", "/api/log", null).body())).isEmpty();
    }

    @Test
    void whenHostingerFailsTheGithubPartsAreStillThereAndTheHostingerPartsSaySo() throws Exception {
        everythingWorks();
        HostingerStubs.websitesServerError(HOSTINGER);

        JsonNode body = overview(false);

        assertThat(body.get("sites").get("count").isNull()).isTrue();
        assertThat(codes(body.get("sites").get("errors"))).containsExactly(HostingerSiteList.UNAVAILABLE);
        assertThat(body.get("sites").get("errors").get(0).get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(body.get("latestDeploys").get("deploys")).isEmpty();
        assertThat(codes(body.get("latestDeploys").get("errors"))).containsExactly(HostingerSiteList.UNAVAILABLE);
        assertThat(codes(body.get("problems").get("errors"))).containsExactly(HostingerSiteList.UNAVAILABLE);
        // GitHub's part is whole.
        assertThat(body.get("repos").get("count").asInt()).isEqualTo(2);
        assertThat(body.get("repos").get("errors")).isEmpty();
        assertThat(body.get("sources").get(0).get("state").asString()).isEqualTo("CONNECTED");
        // The domain list is its own Hostinger endpoint and still answers; Hostinger shows as failing.
        assertThat(kinds(body.get("problems").get("items"))).containsExactly("SOURCE_FAILING", "DOMAIN_EXPIRED",
                "DOMAIN_EXPIRED", "DOMAIN_EXPIRING");
        assertThat(body.get("problems").get("items").get(0).get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(body.get("sources").get(1).get("state").asString()).isNotEqualTo("CONNECTED");
        // The website list was asked once, and nothing that needs it.
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)));
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).isEmpty();
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching("/api/hosting/v1/accounts/.*/git/.*"))))
                .isEmpty();
    }

    @Test
    void aRejectedHostingerTokenIsNotTriedAgainForTheDomains() throws Exception {
        everythingWorks();
        HostingerStubs.websitesUnauthorized(HOSTINGER, TOKEN);

        JsonNode body = overview(false);

        assertThat(codes(body.get("sites").get("errors"))).containsExactly(HostingerSiteList.TOKEN_REJECTED);
        assertThat(codes(body.get("problems").get("errors"))).containsExactly(HostingerSiteList.TOKEN_REJECTED);
        assertThat(body.get("repos").get("count").asInt()).isEqualTo(2);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.DOMAINS_PATH)))).isEmpty();
        assertThat(body.get("sources").get(1).get("state").asString()).isEqualTo("ERROR");
    }

    @Test
    void whenGithubFailsTheHostingerPartsAreStillThereAndTheGithubPartsSaySo() throws Exception {
        everythingWorks();
        GithubStubs.reposServerError(GITHUB);

        JsonNode body = overview(false);

        assertThat(body.get("repos").get("count").isNull()).isTrue();
        JsonNode error = body.get("repos").get("errors").get(0);
        assertThat(error.get("source").asString()).isEqualTo("GITHUB");
        assertThat(error.get("code").asString()).isEqualTo(GithubRepositoryList.UNAVAILABLE);
        assertThat(error.get("message").asString()).isNotBlank();
        assertThat(codes(body.get("problems").get("errors"))).containsExactly(GithubRepositoryList.UNAVAILABLE);
        // Broken links cannot be told without the repositories; everything from Hostinger is there.
        assertThat(kinds(body.get("problems").get("items"))).containsExactly("SOURCE_FAILING", "DEPLOY_FAILED",
                "DOMAIN_EXPIRED", "DOMAIN_EXPIRED", "DOMAIN_EXPIRING");
        assertThat(body.get("problems").get("items").get(0).get("source").asString()).isEqualTo("GITHUB");
        assertThat(body.get("sites").get("count").asInt()).isEqualTo(3);
        assertThat(body.get("latestDeploys").get("deploys")).hasSize(2);
        assertThat(body.get("sources").get(1).get("state").asString()).isEqualTo("CONNECTED");
    }

    @Test
    void aGithubRateLimitCarriesItsWait() throws Exception {
        everythingWorks();
        GithubStubs.reposTooManyRequests(GITHUB, 42);

        JsonNode error = overview(false).get("repos").get("errors").get(0);

        assertThat(error.get("code").asString()).isEqualTo(GithubRepositoryList.RATE_LIMITED);
        assertThat(error.get("retryAfterSeconds").asLong()).isEqualTo(42);
    }

    @Test
    void oneSitesFailingDeployIsAnErrorOfTheDeploysOnly() throws Exception {
        everythingWorks();
        HostingerStubs.buildsServerError(HOSTINGER, USER, "app.example");

        JsonNode body = overview(false);

        assertThat(body.get("latestDeploys").get("deploys")).hasSize(1);
        assertThat(body.get("latestDeploys").get("deploys").get(0).get("domain").asString()).isEqualTo("ok.example");
        assertThat(codes(body.get("latestDeploys").get("errors"))).containsExactly(HostingerSiteList.UNAVAILABLE);
        assertThat(codes(body.get("problems").get("errors"))).containsExactly(HostingerSiteList.UNAVAILABLE);
        assertThat(body.get("sites").get("count").asInt()).isEqualTo(3);
        assertThat(body.get("sites").get("errors")).isEmpty();
    }

    @Test
    void withNothingConnectedEveryPartSaysNotConnectedAndNoSourceIsAsked() throws Exception {
        send("DELETE", "/api/hostinger/token", null);
        send("POST", "/api/github/disconnect", null);

        JsonNode body = overview(false);

        assertThat(body.get("sites").get("count").isNull()).isTrue();
        assertThat(codes(body.get("sites").get("errors"))).containsExactly(HostingerSiteList.NOT_CONNECTED);
        assertThat(body.get("repos").get("count").isNull()).isTrue();
        assertThat(codes(body.get("repos").get("errors"))).containsExactly(GithubRepositoryList.NOT_CONNECTED);
        assertThat(codes(body.get("latestDeploys").get("errors"))).containsExactly(HostingerSiteList.NOT_CONNECTED);
        assertThat(codes(body.get("problems").get("errors")))
                .containsExactly(GithubRepositoryList.NOT_CONNECTED, HostingerSiteList.NOT_CONNECTED);
        JsonNode items = body.get("problems").get("items");
        assertThat(kinds(items)).containsExactly("SOURCE_NOT_CONNECTED", "SOURCE_NOT_CONNECTED");
        assertThat(items.get(0).get("source").asString()).isEqualTo("GITHUB");
        assertThat(items.get(1).get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(body.get("sources").get(0).get("state").asString()).isEqualTo("NOT_CONNECTED");
        assertThat(body.get("sources").get(1).get("state").asString()).isEqualTo("NOT_CONNECTED");
        assertThat(Instant.parse(body.get("fetchedAt").asString())).isEqualTo(clock.instant());
        assertThat(HOSTINGER.getAllServeEvents()).isEmpty();
        assertThat(GITHUB.getAllServeEvents()).isEmpty();
    }

    @Test
    void savedCopiesAreReusedAndRefreshAsksHostingerAgain() throws Exception {
        everythingWorks();
        Instant first = clock.instant();
        overview(false);
        clock.advanceSeconds(10);

        JsonNode cached = overview(false);

        // Nothing new from Hostinger: the website list, domains and builds are the saved copies.
        assertThat(Instant.parse(cached.get("fetchedAt").asString())).isEqualTo(first);
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)));
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.DOMAINS_PATH)));
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(2);
        int gitRequests = HOSTINGER.findAll(getRequestedFor(urlPathMatching("/api/hosting/v1/accounts/.*/git/.*")))
                .size();

        JsonNode refreshed = overview(true);

        assertThat(Instant.parse(refreshed.get("fetchedAt").asString())).isEqualTo(clock.instant());
        HOSTINGER.verify(2, getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)));
        HOSTINGER.verify(2, getRequestedFor(urlPathEqualTo(HostingerStubs.DOMAINS_PATH)));
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(4);
        // The Git connections keep their five-minute copies, as in the Sites list.
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching("/api/hosting/v1/accounts/.*/git/.*"))))
                .hasSize(gitRequests);
    }

    @Test
    void theEndpointIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs", null).body();

        assertThat(docs).contains("\"/api/overview\"", "\"getOverview\"", "\"Overview\"", "\"OverviewCount\"",
                "\"OverviewProblem\"", "\"OverviewProblems\"", "\"OverviewProblemKind\"", "\"OverviewDeploy\"",
                "\"OverviewDeploys\"", "LINK_BROKEN");
    }

    private static String site(String domain, String websiteType) {
        return HostingerStubs.site(domain, USER, 111, true, "2024-01-15T10:30:00+00:00", websiteType);
    }

    private static void sites(String... sites) {
        HostingerStubs.websites(HOSTINGER, List.of(List.of(sites)));
    }

    private static List<String> kinds(JsonNode items) {
        List<String> kinds = new ArrayList<>();
        items.forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    private static List<String> codes(JsonNode errors) {
        List<String> codes = new ArrayList<>();
        errors.forEach(error -> codes.add(error.get("code").asString()));
        return codes;
    }

    private JsonNode overview(boolean refresh) throws Exception {
        HttpResponse<String> response = send("GET", "/api/overview" + (refresh ? "?refresh=true" : ""), null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
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
