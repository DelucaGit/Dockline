package io.github.delucagit.dockline.github;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
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
 * Connecting to GitHub over real HTTP against our API, with GitHub faked by WireMock and time driven by
 * a {@link MutableClock}. Every response and every log line is collected and checked for the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class GithubConnectApiTest {

    private static final String CLIENT_ID = "Ov23liFakeClientId";
    private static final String DEVICE_CODE = "3584d83530557fdd1f46af8289938c8ef79f9dc5";
    private static final String TOKEN = "gho_FakeTokenFromWireMock0123456789abcd";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer GITHUB = GithubStubs.start();

    @DynamicPropertySource
    static void pointAtFakeGithub(DynamicPropertyRegistry registry) {
        registry.add("dockline.github.api-url", GITHUB::baseUrl);
        registry.add("dockline.github.oauth-url", GITHUB::baseUrl);
        registry.add("dockline.github.client-id", () -> CLIENT_ID);
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
        send("POST", "/api/github/disconnect");
        send("DELETE", "/api/log");
        responses.clear();
    }

    @AfterEach
    void tokenAndDeviceCodeNeverLeaveTheBackend(CapturedOutput output) throws Exception {
        responses.add(send("GET", "/api/log").body());
        for (String text : responses) {
            assertThat(text).doesNotContain(TOKEN).doesNotContain(DEVICE_CODE);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(DEVICE_CODE);
        send("POST", "/api/github/disconnect");
    }

    @AfterAll
    static void stopFakeGithub() {
        GITHUB.stop();
    }

    @Test
    void startReturnsTheCodeTheAddressAndTheInterval() throws Exception {
        GithubStubs.deviceCode(GITHUB, DEVICE_CODE, "WDJB-MJHT", 900, 5);

        HttpResponse<String> response = send("POST", "/api/github/connect");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("userCode").asString()).isEqualTo("WDJB-MJHT");
        assertThat(body.get("verificationUri").asString()).isEqualTo("https://github.com/login/device");
        assertThat(body.get("expiresInSeconds").asLong()).isEqualTo(900);
        assertThat(body.get("intervalSeconds").asLong()).isEqualTo(5);
        assertThat(body.properties()).hasSize(4);
        GITHUB.verify(postRequestedFor(urlPathEqualTo(GithubStubs.DEVICE_CODE_PATH))
                .withHeader("Accept", containing("application/json"))
                .withRequestBody(containing("client_id=" + CLIENT_ID))
                .withRequestBody(containing("scope=read%3Auser+repo")));
    }

    @Test
    void pendingThenApprovedSavesTheTokenAndTheStatusBecomesConnected() throws Exception {
        GithubStubs.deviceCode(GITHUB, DEVICE_CODE, "WDJB-MJHT", 900, 5);
        GithubStubs.tokenPending(GITHUB);
        GithubStubs.user(GITHUB, "octocat");
        send("POST", "/api/github/connect");

        // Too early: GitHub is not asked at all.
        assertThat(poll()).isEqualTo("PENDING/5");
        assertThat(tokenRequests()).isZero();

        clock.advanceSeconds(5);
        assertThat(poll()).isEqualTo("PENDING/5");
        assertThat(poll()).isEqualTo("PENDING/5");
        assertThat(tokenRequests()).isEqualTo(1);

        GithubStubs.tokenSuccess(GITHUB, TOKEN);
        clock.advanceSeconds(5);
        assertThat(poll()).isEqualTo("CONNECTED");
        assertThat(poll()).isEqualTo("CONNECTED");
        assertThat(tokenRequests()).isEqualTo(2);

        assertThat(tokenStore.read(Source.GITHUB)).contains(TOKEN);
        assertThat(github()).isEqualTo("CONNECTED: Connected as octocat");
        GITHUB.verify(getRequestedFor(urlPathEqualTo(GithubStubs.USER_PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        GITHUB.verify(postRequestedFor(urlPathEqualTo(GithubStubs.TOKEN_PATH))
                .withRequestBody(containing("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code")));
    }

    @Test
    void slowDownMakesTheIntervalLonger() throws Exception {
        GithubStubs.deviceCode(GITHUB, DEVICE_CODE, "WDJB-MJHT", 900, 5);
        GithubStubs.tokenSlowDown(GITHUB, 10);
        send("POST", "/api/github/connect");

        clock.advanceSeconds(5);
        assertThat(poll()).isEqualTo("PENDING/10");

        GithubStubs.tokenPending(GITHUB);
        clock.advanceSeconds(5);
        assertThat(poll()).isEqualTo("PENDING/10");
        assertThat(tokenRequests()).isEqualTo(1);

        clock.advanceSeconds(5);
        assertThat(poll()).isEqualTo("PENDING/10");
        assertThat(tokenRequests()).isEqualTo(2);
    }

    @Test
    void deniedOnGithub() throws Exception {
        GithubStubs.deviceCode(GITHUB, DEVICE_CODE, "WDJB-MJHT", 900, 5);
        GithubStubs.tokenDenied(GITHUB);
        send("POST", "/api/github/connect");
        clock.advanceSeconds(5);

        assertThat(poll()).startsWith("DENIED: Access was denied");
        assertThat(tokenStore.read(Source.GITHUB)).isEmpty();
        assertThat(github()).isEqualTo("NOT_CONNECTED: null");
    }

    @Test
    void expiredAccordingToGithubOrToTheClock() throws Exception {
        GithubStubs.deviceCode(GITHUB, DEVICE_CODE, "WDJB-MJHT", 900, 5);
        GithubStubs.tokenExpired(GITHUB);
        send("POST", "/api/github/connect");
        clock.advanceSeconds(5);
        assertThat(poll()).startsWith("EXPIRED: The code expired");

        GithubStubs.tokenPending(GITHUB);
        send("POST", "/api/github/connect");
        clock.advanceSeconds(900);
        assertThat(poll()).startsWith("EXPIRED: The code expired");
        assertThat(tokenRequests()).isEqualTo(1);
    }

    @Test
    void anUnexpectedGithubErrorIsShownAndLogged() throws Exception {
        GithubStubs.deviceCode(GITHUB, DEVICE_CODE, "WDJB-MJHT", 900, 5);
        GithubStubs.tokenError(GITHUB, "device_flow_disabled");
        send("POST", "/api/github/connect");
        clock.advanceSeconds(5);

        assertThat(poll()).startsWith("ERROR: GitHub could not finish connecting: device_flow_disabled");
        assertThat(send("GET", "/api/log?source=GITHUB").body()).contains("device_flow_disabled");
    }

    @Test
    void aRevokedTokenTurnsTheStatusRedAndIsLoggedMasked() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.userUnauthorized(GITHUB);

        assertThat(github()).isEqualTo("ERROR: " + GithubStatusProvider.REVOKED);
        github();

        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=GITHUB").body());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("level").asString()).isEqualTo("ERROR");
        assertThat(entry.get("action").asString()).isEqualTo("GET /user");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(401);
        assertThat(entry.get("context").get("request").asString()).contains("/user").contains("with the saved token");
        assertThat(entry.get("context").get("response").asString()).contains("Bad credentials");
    }

    @Test
    void aRateLimitMakesTheStatusLimited() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.userRateLimited(GITHUB);

        assertThat(github()).startsWith("LIMITED: GitHub's rate limit is reached");
    }

    @Test
    void anUnreachableGithubMakesTheStatusLimitedNotAnError() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GITHUB.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(GithubStubs.USER_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        assertThat(github()).startsWith("LIMITED: Could not reach GitHub");
    }

    @Test
    void theCheckIsCachedForAMinuteUnlessRecheckedOrReconnected() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.user(GITHUB, "octocat");

        github();
        github();
        assertThat(userRequests()).isEqualTo(1);

        clock.advance(GithubStatusProvider.CACHE_TTL.minusSeconds(1));
        github();
        assertThat(userRequests()).isEqualTo(1);

        clock.advanceSeconds(1);
        github();
        assertThat(userRequests()).isEqualTo(2);

        GithubStubs.userUnauthorized(GITHUB);
        HttpResponse<String> recheck = send("POST", "/api/github/recheck");
        assertThat(recheck.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(recheck.body()).get("state").asString()).isEqualTo("ERROR");
        assertThat(userRequests()).isEqualTo(3);
        assertThat(github()).startsWith("ERROR");
        assertThat(userRequests()).isEqualTo(3);
    }

    @Test
    void disconnectDeletesTheToken() throws Exception {
        tokenStore.save(Source.GITHUB, TOKEN);
        GithubStubs.user(GITHUB, "octocat");
        assertThat(github()).startsWith("CONNECTED");

        HttpResponse<String> response = send("POST", "/api/github/disconnect");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(tokenStore.read(Source.GITHUB)).isEmpty();
        assertThat(github()).isEqualTo("NOT_CONNECTED: null");
        assertThat(poll()).isEqualTo("NOT_STARTED");
    }

    @Test
    void theEndpointsAreInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs").body();

        assertThat(docs).contains("\"/api/github/connect\"", "\"/api/github/connect/poll\"",
                "\"/api/github/disconnect\"", "\"/api/github/recheck\"", "\"GithubDeviceCode\"",
                "\"GithubConnectProgress\"", "\"GithubConnectState\"", "slow down");
    }

    /** "STATE/interval" for PENDING, "STATE: message" for DENIED/EXPIRED/ERROR, else "STATE". */
    private String poll() throws Exception {
        HttpResponse<String> response = send("POST", "/api/github/connect/poll");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        String state = body.get("state").asString();
        if (state.equals("PENDING")) {
            return state + "/" + body.get("intervalSeconds").asLong();
        }
        JsonNode message = body.get("message");
        return message == null || message.isNull() ? state : state + ": " + message.asString();
    }

    /** "STATE: message" of GITHUB in GET /api/status. */
    private String github() throws Exception {
        JsonNode github = JSON.readTree(send("GET", "/api/status").body()).get("sources").get(0);
        assertThat(github.get("source").asString()).isEqualTo("GITHUB");
        JsonNode message = github.get("message");
        return github.get("state").asString() + ": " + (message == null || message.isNull() ? null : message.asString());
    }

    private int tokenRequests() {
        return GITHUB.findAll(postRequestedFor(urlPathEqualTo(GithubStubs.TOKEN_PATH))).size();
    }

    private int userRequests() {
        return GITHUB.findAll(getRequestedFor(urlPathEqualTo(GithubStubs.USER_PATH))).size();
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        responses.add(response.body());
        return response;
    }
}
