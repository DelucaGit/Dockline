package io.github.delucagit.dockline.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
 * {@code GET /api/github/repositories} over real HTTP, with GitHub faked by WireMock and time driven by a
 * {@link MutableClock}. Every response and every log line is collected and checked for the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GithubRepositoriesApiTest {

    private static final String TOKEN = "gho_FakeRepoListToken0123456789abcdefXYZ";
    private static final String PATH = "/api/github/repositories";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer GITHUB = GithubStubs.start();
    /** Somewhere else entirely: a next-page link pointing here must not be followed. */
    private static final WireMockServer FOREIGN = GithubStubs.start();

    @DynamicPropertySource
    static void pointAtFakeGithub(DynamicPropertyRegistry registry) {
        registry.add("dockline.github.api-url", GITHUB::baseUrl);
        registry.add("dockline.github.oauth-url", GITHUB::baseUrl);
        registry.add("dockline.github.client-id", () -> "Ov23liFakeClientId");
    }

    @TestConfiguration
    static class Clocks {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock();
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
        GITHUB.resetAll();
        FOREIGN.resetAll();
        send("POST", "/api/github/disconnect");
        send("DELETE", "/api/log");
        responses.clear();
    }

    @AfterEach
    void theTokenNeverLeavesTheBackend(CapturedOutput output) throws Exception {
        responses.add(send("GET", "/api/log").body());
        for (String text : responses) {
            assertThat(text).doesNotContain(TOKEN);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN);
        send("POST", "/api/github/disconnect");
    }

    @AfterAll
    static void stopFakeServers() {
        GITHUB.stop();
        FOREIGN.stop();
    }

    @Test
    void allPagesAreFetchedAndTheListIsSortedByLastPush() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.repos(GITHUB, List.of(
                List.of(GithubStubs.repo("octo", "middle", false, "2026-10-01T10:00:00Z"),
                        GithubStubs.repo("octo", "never-pushed", true, null)),
                List.of(GithubStubs.repo("octo", "newest", true, "2026-11-12T08:15:30Z")),
                List.of(GithubStubs.repo("acme", "oldest", false, "2024-01-02T03:04:05Z"))));

        HttpResponse<String> response = send("GET", PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(3);
        assertThat(body.get("fetchedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(body.get("truncated").asBoolean()).isFalse();
        JsonNode repos = body.get("repositories");
        assertThat(repos.valueStream().map(r -> r.get("fullName").asString()))
                .containsExactly("octo/newest", "octo/middle", "acme/oldest", "octo/never-pushed");
        JsonNode newest = repos.get(0);
        assertThat(newest.get("name").asString()).isEqualTo("newest");
        assertThat(newest.get("visibility").asString()).isEqualTo("PRIVATE");
        assertThat(newest.get("lastPushAt").asString()).isEqualTo("2026-11-12T08:15:30Z");
        assertThat(newest.get("htmlUrl").asString()).isEqualTo("https://github.com/octo/newest");
        assertThat(newest.get("fork").asBoolean()).isFalse();
        assertThat(newest.get("archived").asBoolean()).isFalse();
        assertThat(repos.get(1).get("visibility").asString()).isEqualTo("PUBLIC");
        assertThat(repos.get(3).get("lastPushAt").isNull()).isTrue();

        List<LoggedRequest> requests = GITHUB.findAll(getRequestedFor(urlPathEqualTo(GithubStubs.REPOS_PATH)));
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).getUrl()).isEqualTo(GithubStubs.REPOS_PATH
                + "?visibility=all&affiliation=owner,collaborator,organization_member&sort=pushed&direction=desc&per_page=100");
        assertThat(requests.get(1).queryParameter("page").firstValue()).isEqualTo("2");
        assertThat(requests.get(2).queryParameter("page").firstValue()).isEqualTo("3");
        GITHUB.verify(3, getRequestedFor(urlPathEqualTo(GithubStubs.REPOS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN))
                .withHeader("Accept", equalTo("application/vnd.github+json"))
                .withHeader("X-GitHub-Api-Version", equalTo("2022-11-28")));
    }

    @Test
    void everyCallAsksGithubAgain() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.repos(GITHUB, List.of(List.of(GithubStubs.repo("octo", "one", false, "2026-10-01T10:00:00Z"))));

        send("GET", PATH);
        clock.advanceSeconds(5);
        JsonNode second = JSON.readTree(send("GET", PATH).body());

        assertThat(second.get("fetchedAt").asString()).isEqualTo("2026-01-01T12:00:05Z");
        assertThat(GITHUB.findAll(getRequestedFor(urlPathEqualTo(GithubStubs.REPOS_PATH)))).hasSize(2);
    }

    @Test
    void anEmptyAccountIsAnEmptyList() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.repos(GITHUB, List.of(List.of()));

        HttpResponse<String> response = send("GET", PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("repositories")).isEmpty();
    }

    @Test
    void notConnectedIsItsOwnProblemAndGithubIsNotCalled() throws Exception {
        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 409, GithubRepositoryList.NOT_CONNECTED);
        assertThat(JSON.readTree(response.body()).get("detail").asString()).contains("Connect to GitHub in Settings");
        assertThat(GITHUB.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(JSON.readTree(send("GET", "/api/log").body())).isEmpty();
    }

    @Test
    void aRevokedTokenIsReportedTurnsTheStatusRedAndIsLoggedMasked() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.reposUnauthorized(GITHUB);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 409, GithubRepositoryList.TOKEN_REJECTED);
        assertThat(github()).isEqualTo("ERROR: " + GithubStatusProvider.REVOKED);
        // The status came from the repository call; GET /user was not needed.
        assertThat(GITHUB.findAll(getRequestedFor(urlPathEqualTo(GithubStubs.USER_PATH)))).isEmpty();
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("level").asString()).isEqualTo("ERROR");
        assertThat(entry.get("action").asString()).isEqualTo("GET /user/repos");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(401);
        assertThat(entry.get("context").get("request").asString()).contains("/user/repos").contains("with the saved token");
        assertThat(entry.get("context").get("response").asString()).contains("Bad credentials");
    }

    @Test
    void thePrimaryRateLimitGivesRetryAfterUntilTheReset() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.reposRateLimited(GITHUB, clock.instant().getEpochSecond() + 3600);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 429, GithubRepositoryList.RATE_LIMITED);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("3600");
        assertThat(github()).startsWith("LIMITED: GitHub's rate limit is reached");
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("level").asString()).isEqualTo("WARN");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(403);
    }

    @Test
    void aSecondaryRateLimitPassesGithubsRetryAfterOn() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.reposTooManyRequests(GITHUB, 60);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 429, GithubRepositoryList.RATE_LIMITED);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(onlyLogEntry().get("statusCode").asInt()).isEqualTo(429);
    }

    @Test
    void aGithubServerErrorIsUnavailable() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.reposServerError(GITHUB);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, GithubRepositoryList.UNAVAILABLE);
        assertThat(github()).isEqualTo("LIMITED: GitHub answered with an error (HTTP 503). Try again later.");
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("statusCode").asInt()).isEqualTo(503);
        assertThat(entry.get("context").get("response").asString()).contains("Service Unavailable");
    }

    @Test
    void anUnreachableGithubIsUnavailable() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GITHUB.stubFor(get(urlPathEqualTo(GithubStubs.REPOS_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, GithubRepositoryList.UNAVAILABLE);
        assertThat(github()).startsWith("LIMITED: Could not reach GitHub");
        assertThat(onlyLogEntry().get("context").get("stackTrace").asString()).isNotBlank();
    }

    @Test
    void aNextPageLinkToAnotherHostIsNotFollowed() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.reposWithForeignNextLink(GITHUB, FOREIGN.baseUrl() + "/user/repos?page=2",
                List.of(GithubStubs.repo("octo", "one", false, "2026-10-01T10:00:00Z")));

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, GithubRepositoryList.UNAVAILABLE);
        assertThat(FOREIGN.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(onlyLogEntry().get("message").asString()).contains("did not follow");
    }

    @Test
    void theListStopsAfterFiftyPagesAndSaysSo() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.repos(GITHUB, IntStream.rangeClosed(1, GithubRepositoryList.MAX_PAGES + 1)
                .mapToObj(page -> List.of(GithubStubs.repo("octo", "repo-" + page, false, "2026-10-01T10:00:00Z")))
                .toList());

        JsonNode body = JSON.readTree(send("GET", PATH).body());

        assertThat(body.get("truncated").asBoolean()).isTrue();
        assertThat(body.get("repositories")).hasSize(GithubRepositoryList.MAX_PAGES);
        assertThat(GITHUB.findAll(getRequestedFor(urlPathEqualTo(GithubStubs.REPOS_PATH))))
                .hasSize(GithubRepositoryList.MAX_PAGES);
        assertThat(onlyLogEntry().get("level").asString()).isEqualTo("WARN");
    }

    @Test
    void theEndpointIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs").body();

        assertThat(docs).contains("\"/api/github/repositories\"", "\"listGithubRepositories\"", "\"GithubRepositories\"",
                "\"GithubRepository\"", "\"GithubVisibility\"", "GITHUB_NOT_CONNECTED", "GITHUB_TOKEN_REJECTED",
                "GITHUB_RATE_LIMITED", "GITHUB_UNAVAILABLE");
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

    private JsonNode onlyLogEntry() throws Exception {
        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=GITHUB").body());
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    /** "STATE: message" of GITHUB in GET /api/status. */
    private String github() throws Exception {
        JsonNode github = JSON.readTree(send("GET", "/api/status").body()).get("sources").get(0);
        assertThat(github.get("source").asString()).isEqualTo("GITHUB");
        JsonNode message = github.get("message");
        return github.get("state").asString() + ": " + (message == null || message.isNull() ? null : message.asString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        responses.add(response.body());
        return response;
    }
}
