package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.delucagit.dockline.ClockAdvancingWaiter;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
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
 * The deploy status endpoint ({@code GET /api/sites/deploys}) over real HTTP, with Hostinger faked by WireMock
 * and time driven by a {@link MutableClock}. Every response and every log line is collected and checked for
 * the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SiteDeploysApiTest {

    private static final String TOKEN = "hst_FakeSiteDeploysToken0123456789abcdefXY";
    private static final String USER = "u100";
    private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer HOSTINGER = HostingerStubs.start();
    private static final String BUILDS_PATHS = "/api/hosting/v1/accounts/.*/nodejs/builds";

    @DynamicPropertySource
    static void pointAtFakes(DynamicPropertyRegistry registry) {
        registry.add("dockline.hostinger.api-url", HOSTINGER::baseUrl);
        registry.add("dockline.settings.dir",
                () -> Path.of("target", "test-settings", "site-deploys-" + UUID.randomUUID()).toAbsolutePath().toString());
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

    private final List<String> responses = new ArrayList<>();

    @BeforeEach
    void cleanStart() throws Exception {
        // Two minutes later: the Hostinger request cap and any Retry-After block from earlier tests are over.
        clock.advanceSeconds(121);
        HOSTINGER.resetAll();
        // Removing the token also forgets every saved Hostinger answer.
        send("DELETE", "/api/hostinger/token", null);
        send("DELETE", "/api/log", null);
        responses.clear();
        tokenStore.save(Source.HOSTINGER, TOKEN);
    }

    @AfterEach
    void theTokenNeverLeavesTheBackend(CapturedOutput output) throws Exception {
        responses.add(send("GET", "/api/log", null).body());
        for (String text : responses) {
            assertThat(text).doesNotContain(TOKEN);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN);
        send("DELETE", "/api/hostinger/token", null);
    }

    @AfterAll
    static void stopFakeServer() {
        HOSTINGER.stop();
    }

    @Test
    void aSucceededBuildIsShownWithItsTimeAndCommit() throws Exception {
        sites(nodejs("App.example"));
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "App.example", SHA, "Initial commit");

        HttpResponse<String> response = send("GET", "/api/sites/deploys", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(2);
        assertThat(Instant.parse(body.get("fetchedAt").asString())).isEqualTo(clock.instant());
        assertThat(body.get("deploys")).hasSize(1);
        JsonNode deploy = body.get("deploys").get(0);
        assertThat(deploy.properties()).hasSize(6);
        assertThat(deploy.get("domain").asString()).isEqualTo("app.example");
        assertThat(deploy.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(deploy.get("at").asString()).isEqualTo("2024-05-29T05:51:02Z");
        assertThat(deploy.get("commit").properties()).hasSize(2);
        assertThat(deploy.get("commit").get("sha").asString()).isEqualTo(SHA);
        assertThat(deploy.get("commit").get("message").asString()).isEqualTo("Initial commit");
        assertThat(deploy.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(deploy.get("problems")).isEmpty();
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.nodejsBuildsPath(USER, "App.example")))
                .withQueryParam("page", equalTo("1")).withQueryParam("per_page", equalTo("100"))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        assertThat(JSON.readTree(send("GET", "/api/log", null).body())).isEmpty();
    }

    @Test
    void aFailedBuildIsShownAsFailed() throws Exception {
        sites(nodejs("app.example"));
        HostingerStubs.buildFailed(HOSTINGER, USER, "app.example", SHA, "Break the build");

        JsonNode deploy = deploys().get(0);

        assertThat(deploy.get("status").asString()).isEqualTo("FAILED");
        assertThat(deploy.get("at").asString()).isEqualTo("2024-05-29T06:10:41Z");
        assertThat(deploy.get("commit").get("message").asString()).isEqualTo("Break the build");
        assertThat(deploy.get("source").asString()).isEqualTo("HOSTINGER");
    }

    @Test
    void aPendingOrRunningBuildIsInProgress() throws Exception {
        sites(nodejs("pending.example"), nodejs("running.example"));
        HostingerStubs.buildInProgress(HOSTINGER, USER, "pending.example", "pending");
        HostingerStubs.buildInProgress(HOSTINGER, USER, "running.example", "running");

        JsonNode deploys = deploys();

        JsonNode pending = deploys.get(0);
        assertThat(pending.get("domain").asString()).isEqualTo("pending.example");
        assertThat(pending.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(pending.get("at").asString()).isEqualTo("2024-05-29T07:00:05Z");
        // Hostinger has not cloned the repository yet: no commit.
        assertThat(pending.get("commit").isNull()).isTrue();
        JsonNode running = deploys.get(1);
        assertThat(running.get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(running.get("commit").get("message").asString()).isEqualTo("Add contact page");
    }

    @Test
    void anUnknownStateIsUnknownAndAnArchiveBuildHasNoCommit() throws Exception {
        sites(nodejs("archive.example"), nodejs("odd.example"));
        HostingerStubs.builds(HOSTINGER, USER, "archive.example", HostingerStubs.archiveBuild(
                "9c23a3b5-4cad-4e2e-8fd1-939bc98df460", "completed", "2024-06-01T10:00:00Z", "2024-06-01T10:01:00Z"));
        // No updated_at: the time falls back to created_at.
        HostingerStubs.builds(HOSTINGER, USER, "odd.example", """
                {"uuid":"ad34b4c6-5dbe-4f3f-9fe2-a4acda9ee571","state":"cancelled","created_at":"2024-06-02T10:00:00Z"}""");

        JsonNode deploys = deploys();

        assertThat(deploys.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(deploys.get(0).get("commit").isNull()).isTrue();
        assertThat(deploys.get(1).get("status").asString()).isEqualTo("UNKNOWN");
        assertThat(deploys.get(1).get("at").asString()).isEqualTo("2024-06-02T10:00:00Z");
        assertThat(deploys.get(1).get("commit").isNull()).isTrue();
        assertThat(deploys.get(1).get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(deploys.get(1).get("problems")).isEmpty();
    }

    @Test
    void aSiteThatIsNotNodejsHasNoDeployInformationAndIsNotAskedAbout() throws Exception {
        sites(site("blog.example", "wordpress"), site("plain.example", "other"));

        JsonNode body = JSON.readTree(send("GET", "/api/sites/deploys?refresh=true", null).body());

        assertThat(body.get("deploys")).hasSize(2);
        for (JsonNode deploy : body.get("deploys")) {
            assertThat(deploy.get("status").asString()).isEqualTo("NONE");
            assertThat(deploy.get("source").asString()).isEqualTo("NONE");
            assertThat(deploy.get("at").isNull()).isTrue();
            assertThat(deploy.get("commit").isNull()).isTrue();
            assertThat(deploy.get("problems")).isEmpty();
        }
        assertThat(Instant.parse(body.get("fetchedAt").asString())).isEqualTo(clock.instant());
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).isEmpty();
    }

    @Test
    void aNodejsSiteWithoutBuildsHasNoDeployInformation() throws Exception {
        sites(nodejs("new.example"));
        HostingerStubs.buildsEmpty(HOSTINGER, USER, "new.example");

        JsonNode deploy = deploys().get(0);

        assertThat(deploy.get("status").asString()).isEqualTo("NONE");
        assertThat(deploy.get("source").asString()).isEqualTo("NONE");
        assertThat(deploy.get("at").isNull()).isTrue();
        assertThat(deploy.get("problems")).isEmpty();
    }

    @Test
    void a404Or422IsNoDeployInformationAndIsNotAskedAgainWithinTheCacheTime() throws Exception {
        sites(nodejs("gone.example"), nodejs("odd.example"));
        HostingerStubs.buildsNotFound(HOSTINGER, USER, "gone.example");
        HOSTINGER.stubFor(get(urlPathEqualTo(HostingerStubs.nodejsBuildsPath(USER, "odd.example")))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"message\":\"Unprocessable\",\"correlation_id\":\"c0ffee00-0000-4000-8000-000000000045\"}")));

        JsonNode first = deploys();
        clock.advanceSeconds(10);
        JsonNode second = deploys();

        for (JsonNode deploys : List.of(first, second)) {
            for (JsonNode deploy : deploys) {
                assertThat(deploy.get("status").asString()).isEqualTo("NONE");
                assertThat(deploy.get("source").asString()).isEqualTo("NONE");
                assertThat(deploy.get("problems")).isEmpty();
            }
        }
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(2);
        // Not a failure: nothing in the Log section, and the Hostinger dot stays green.
        assertThat(JSON.readTree(send("GET", "/api/log", null).body())).isEmpty();
    }

    @Test
    void theNewestBuildIsPickedWhenTheListIsOldestFirst() throws Exception {
        sites(nodejs("app.example"));
        HostingerStubs.builds(HOSTINGER, USER, "app.example",
                HostingerStubs.build("11111111-1111-4111-8111-111111111111", "completed", "2024-05-01T10:00:00Z",
                        "2024-05-01T10:02:00Z", SHA, "Oldest"),
                HostingerStubs.build("33333333-3333-4333-8333-333333333333", "failed", "2024-05-03T10:00:00Z",
                        "2024-05-03T10:02:00Z", "c3" + SHA.substring(2), "Newest"),
                HostingerStubs.build("22222222-2222-4222-8222-222222222222", "completed", "2024-05-02T10:00:00Z",
                        "2024-05-02T10:02:00Z", "b2" + SHA.substring(2), "Middle"));

        JsonNode deploy = deploys().get(0);

        assertThat(deploy.get("status").asString()).isEqualTo("FAILED");
        assertThat(deploy.get("at").asString()).isEqualTo("2024-05-03T10:02:00Z");
        assertThat(deploy.get("commit").get("message").asString()).isEqualTo("Newest");
    }

    @Test
    void withMoreBuildsThanOnePageTheLastPageIsReadToo() throws Exception {
        sites(nodejs("old-first.example"), nodejs("new-first.example"));
        // Oldest first: the newest build is on the last page (3 of 3, with 2 per page and 5 builds).
        HostingerStubs.buildsPage(HOSTINGER, USER, "old-first.example", 1, 2, 5,
                HostingerStubs.build("11111111-1111-4111-8111-111111111111", "completed", "2024-05-01T10:00:00Z",
                        "2024-05-01T10:02:00Z", SHA, "First"),
                HostingerStubs.build("22222222-2222-4222-8222-222222222222", "completed", "2024-05-02T10:00:00Z",
                        "2024-05-02T10:02:00Z", SHA, "Second"));
        HostingerStubs.buildsPage(HOSTINGER, USER, "old-first.example", 3, 2, 5,
                HostingerStubs.build("55555555-5555-4555-8555-555555555555", "running", "2024-05-05T10:00:00Z",
                        "2024-05-05T10:00:30Z", SHA, "Fifth"));
        // Newest first: the last page only has older builds.
        HostingerStubs.buildsPage(HOSTINGER, USER, "new-first.example", 1, 2, 3,
                HostingerStubs.build("99999999-9999-4999-8999-999999999999", "completed", "2024-05-09T10:00:00Z",
                        "2024-05-09T10:02:00Z", SHA, "Ninth"),
                HostingerStubs.build("88888888-8888-4888-8888-888888888888", "failed", "2024-05-08T10:00:00Z",
                        "2024-05-08T10:02:00Z", SHA, "Eighth"));
        HostingerStubs.buildsPage(HOSTINGER, USER, "new-first.example", 2, 2, 3,
                HostingerStubs.build("77777777-7777-4777-8777-777777777777", "failed", "2024-05-07T10:00:00Z",
                        "2024-05-07T10:02:00Z", SHA, "Seventh"));

        JsonNode deploys = deploys();

        assertThat(deploys.get(0).get("domain").asString()).isEqualTo("new-first.example");
        assertThat(deploys.get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(deploys.get(0).get("commit").get("message").asString()).isEqualTo("Ninth");
        assertThat(deploys.get(1).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(deploys.get(1).get("commit").get("message").asString()).isEqualTo("Fifth");
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.nodejsBuildsPath(USER, "old-first.example")))
                .withQueryParam("page", equalTo("3")).withQueryParam("per_page", equalTo("100")));
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.nodejsBuildsPath(USER, "new-first.example")))
                .withQueryParam("page", equalTo("2")));
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(4);
    }

    @Test
    void oneSiteFailingDoesNotBreakTheOthersAndIsWrittenToTheLog() throws Exception {
        sites(nodejs("a.example"), nodejs("b.example"), nodejs("c.example"), nodejs("d.example"));
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "a.example", SHA, "Initial commit");
        HostingerStubs.buildsServerError(HOSTINGER, USER, "b.example");
        HOSTINGER.stubFor(get(urlPathEqualTo(HostingerStubs.nodejsBuildsPath(USER, "c.example")))
                .willReturn(okJson("{\"data\":\"not a list\"}")));
        HostingerStubs.buildFailed(HOSTINGER, USER, "d.example", SHA, "Break the build");

        HttpResponse<String> response = send("GET", "/api/sites/deploys", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode deploys = JSON.readTree(response.body()).get("deploys");
        assertThat(deploys.valueStream().map(deploy -> deploy.get("domain").asString() + " "
                + deploy.get("status").asString() + " " + deploy.get("problems").size()))
                .containsExactly("a.example SUCCEEDED 0", "b.example UNKNOWN 1", "c.example UNKNOWN 1",
                        "d.example FAILED 0");
        for (int failed : List.of(1, 2)) {
            JsonNode deploy = deploys.get(failed);
            assertThat(deploy.get("source").asString()).isEqualTo("HOSTINGER");
            assertThat(deploy.get("at").isNull()).isTrue();
            assertThat(deploy.get("commit").isNull()).isTrue();
            JsonNode problem = deploy.get("problems").get(0);
            assertThat(problem.get("source").asString()).isEqualTo("HOSTINGER");
            assertThat(problem.get("code").asString()).isEqualTo(HostingerSiteList.UNAVAILABLE);
            assertThat(problem.get("message").asString()).isNotBlank();
            assertThat(problem.get("retryAfterSeconds").isNull()).isTrue();
        }
        // Newest first: c's unreadable answer, then b's 500.
        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body());
        assertThat(entries).hasSize(2);
        assertThat(entries.get(1).get("level").asString()).isEqualTo("WARN");
        assertThat(entries.get(1).get("action").asString())
                .isEqualTo("GET " + HostingerStubs.nodejsBuildsPath(USER, "b.example"));
        assertThat(entries.get(1).get("statusCode").asInt()).isEqualTo(500);
        assertThat(entries.get(0).get("level").asString()).isEqualTo("WARN");
        assertThat(entries.get(0).get("action").asString())
                .isEqualTo("GET " + HostingerStubs.nodejsBuildsPath(USER, "c.example"));

        // A failure is not saved: once Hostinger answers again, the site is shown at once.
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "b.example", SHA, "Initial commit");
        assertThat(deploys().get(1).get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void theAnswersAreReusedFor20SecondsAndRefreshSkipsThem() throws Exception {
        sites(nodejs("app.example"));
        HostingerStubs.buildInProgress(HOSTINGER, USER, "app.example", "running");

        JsonNode first = JSON.readTree(send("GET", "/api/sites/deploys", null).body());
        Instant fetchedAt = clock.instant();
        assertThat(first.get("deploys").get(0).get("status").asString()).isEqualTo("IN_PROGRESS");

        // The build finishes, but for 20 seconds the saved answer is used.
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "app.example", SHA, "Initial commit");
        clock.advanceSeconds(19);
        JsonNode reused = JSON.readTree(send("GET", "/api/sites/deploys", null).body());
        assertThat(reused.get("deploys").get(0).get("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(Instant.parse(reused.get("fetchedAt").asString())).isEqualTo(fetchedAt);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(1);

        // refresh=true asks again at once, without asking for the website list again.
        JsonNode refreshed = JSON.readTree(send("GET", "/api/sites/deploys?refresh=true", null).body());
        assertThat(refreshed.get("deploys").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(Instant.parse(refreshed.get("fetchedAt").asString())).isEqualTo(clock.instant());
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(2);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)))).hasSize(1);

        // 20 seconds after that answer the saved copy is too old: the next call asks Hostinger by itself.
        HostingerStubs.buildFailed(HOSTINGER, USER, "app.example", SHA, "Break the build");
        clock.advanceSeconds(20);
        assertThat(deploys().get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(3);
        assertThat(HostingerClient.BUILDS_TTL).hasSeconds(20);
    }

    @Test
    void aRateLimitMidwayStopsAskingAndIsReportedForTheSitesLeft() throws Exception {
        sites(nodejs("a.example"), nodejs("b.example"), nodejs("c.example"), nodejs("d.example"),
                site("e.example", "wordpress"));
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "a.example", SHA, "Initial commit");
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "b.example", SHA, "Initial commit");
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "c.example", SHA, "Initial commit");
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "d.example", SHA, "Initial commit");
        // c and d are asked first, so their answers are saved when the rate limit comes.
        deploys();
        HOSTINGER.resetRequests();
        HostingerStubs.buildsRateLimited(HOSTINGER, USER, "b.example", 42);
        HostingerStubs.buildFailed(HOSTINGER, USER, "d.example", SHA, "Break the build");
        clock.advanceSeconds(5);

        HttpResponse<String> response = send("GET", "/api/sites/deploys?refresh=true", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode deploys = JSON.readTree(response.body()).get("deploys");
        assertThat(deploys.valueStream().map(deploy -> deploy.get("domain").asString() + " "
                + deploy.get("status").asString() + " " + deploy.get("problems").size()))
                .containsExactly("a.example SUCCEEDED 0", "b.example UNKNOWN 1", "c.example SUCCEEDED 0",
                        "d.example SUCCEEDED 0", "e.example NONE 0");
        JsonNode problem = deploys.get(1).get("problems").get(0);
        assertThat(problem.get("code").asString()).isEqualTo(HostingerSiteList.RATE_LIMITED);
        assertThat(problem.get("retryAfterSeconds").asLong()).isEqualTo(42);
        // a and b were asked; c and d came from the saved answers.
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(2);

        // Once their saved answers are too old, the sites left get the same problem, still without a request
        // (a was refreshed 19 seconds ago, so its saved answer is still used).
        clock.advanceSeconds(19);
        HOSTINGER.resetRequests();
        JsonNode later = deploys();
        assertThat(later.valueStream().map(deploy -> deploy.get("domain").asString() + " "
                + deploy.get("status").asString() + " " + deploy.get("problems").size()))
                .containsExactly("a.example SUCCEEDED 0", "b.example UNKNOWN 1", "c.example UNKNOWN 1",
                        "d.example UNKNOWN 1", "e.example NONE 0");
        assertThat(later.get(3).get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.RATE_LIMITED);
        assertThat(later.get(3).get("problems").get(0).get("retryAfterSeconds").asLong()).isEqualTo(23);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).isEmpty();
    }

    @Test
    void aRejectedTokenOnTheBuildsEndpointIsReportedPerSite() throws Exception {
        sites(nodejs("a.example"), nodejs("b.example"));
        HostingerStubs.buildsUnauthorized(HOSTINGER, USER, "a.example", TOKEN);
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "b.example", SHA, "Initial commit");

        JsonNode deploys = deploys();

        for (JsonNode deploy : deploys) {
            assertThat(deploy.get("status").asString()).isEqualTo("UNKNOWN");
            assertThat(deploy.get("problems").get(0).get("code").asString()).isEqualTo(HostingerSiteList.TOKEN_REJECTED);
        }
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).hasSize(1);
    }

    @Test
    void whenTheWebsiteListCannotBeReadTheAnswerIsTheUsualHostingerProblem() throws Exception {
        HostingerStubs.websitesUnauthorized(HOSTINGER, TOKEN);
        assertProblem(send("GET", "/api/sites/deploys", null), 409, HostingerSiteList.TOKEN_REJECTED);

        HostingerStubs.websitesServerError(HOSTINGER);
        assertProblem(send("GET", "/api/sites/deploys", null), 502, HostingerSiteList.UNAVAILABLE);

        HostingerStubs.websitesRateLimited(HOSTINGER, 42);
        HttpResponse<String> limited = send("GET", "/api/sites/deploys", null);
        assertProblem(limited, 429, HostingerSiteList.RATE_LIMITED);
        assertThat(limited.headers().firstValue("Retry-After")).hasValue("42");
        assertThat(JSON.readTree(limited.body()).get("retryAfterSeconds").asLong()).isEqualTo(42);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathMatching(BUILDS_PATHS)))).isEmpty();
    }

    @Test
    void withoutAHostingerTokenTheAnswerIsNotConnected() throws Exception {
        send("DELETE", "/api/hostinger/token", null);

        assertProblem(send("GET", "/api/sites/deploys", null), 409, HostingerSiteList.NOT_CONNECTED);
        assertThat(HOSTINGER.getAllServeEvents()).isEmpty();
    }

    @Test
    void theEndpointIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs", null).body();

        assertThat(docs).contains("\"/api/sites/deploys\"", "\"listSiteDeploys\"", "\"SiteDeploys\"", "\"SiteDeploy\"",
                "\"SiteDeployStatus\"", "\"SiteDeploySource\"", "\"SiteDeployCommit\"", "IN_PROGRESS");
    }

    private static String nodejs(String domain) {
        return site(domain, "nodejs");
    }

    private static String site(String domain, String websiteType) {
        return HostingerStubs.site(domain, USER, 111, true, "2024-01-15T10:30:00+00:00", websiteType);
    }

    /** The website list: these websites, all in the hosting account {@link #USER}. */
    private static void sites(String... sites) {
        HostingerStubs.websites(HOSTINGER, List.of(List.of(sites)));
    }

    /** The {@code deploys} of a successful {@code GET /api/sites/deploys}. */
    private JsonNode deploys() throws Exception {
        HttpResponse<String> response = send("GET", "/api/sites/deploys", null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("deploys");
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
