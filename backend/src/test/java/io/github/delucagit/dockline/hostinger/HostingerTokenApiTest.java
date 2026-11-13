package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
 * Saving, checking and removing the Hostinger token over real HTTP against our API, with Hostinger faked by
 * WireMock and time driven by a {@link MutableClock}. Every response and every log line is checked for the tokens.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class HostingerTokenApiTest {

    private static final String TOKEN = "hst_FakeTokenFromWireMock0123456789abcd";
    private static final String OTHER_TOKEN = "hst_AnotherFakeToken9876543210zyxwvu";
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer HOSTINGER = HostingerStubs.start();

    @DynamicPropertySource
    static void pointAtFakeHostinger(DynamicPropertyRegistry registry) {
        registry.add("dockline.hostinger.api-url", HOSTINGER::baseUrl);
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
        // A minute later: the Hostinger request cap and saved answers from earlier tests are gone.
        clock.advanceSeconds(61);
        HOSTINGER.resetAll();
        send("DELETE", "/api/hostinger/token", null);
        send("DELETE", "/api/log", null);
        responses.clear();
    }

    @AfterEach
    void tokensNeverLeaveTheBackend(CapturedOutput output) throws Exception {
        responses.add(send("GET", "/api/log", null).body());
        for (String text : responses) {
            assertThat(text).doesNotContain(TOKEN).doesNotContain(OTHER_TOKEN);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN).doesNotContain(OTHER_TOKEN);
        send("DELETE", "/api/hostinger/token", null);
    }

    @AfterAll
    static void stopFakeHostinger() {
        HOSTINGER.stop();
    }

    @Test
    void aValidTokenIsCheckedThenSavedAndTheStatusIsConnected() throws Exception {
        HostingerStubs.subscriptionsOk(HOSTINGER);

        HttpResponse<String> response = save(TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(body.get("state").asString()).isEqualTo("CONNECTED");
        assertThat(tokenStore.read(Source.HOSTINGER)).contains(TOKEN);
        HOSTINGER.verify(getRequestedFor(urlPathEqualTo(HostingerStubs.SUBSCRIPTIONS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        assertThat(hostinger()).isEqualTo("CONNECTED: null");
        assertThat(subscriptionRequests()).as("the status reuses the check made when saving").isEqualTo(1);
    }

    @Test
    void surroundingWhitespaceIsIgnored() throws Exception {
        HostingerStubs.subscriptionsOk(HOSTINGER);

        assertThat(save("  " + TOKEN + "\n").statusCode()).isEqualTo(200);

        assertThat(tokenStore.read(Source.HOSTINGER)).contains(TOKEN);
    }

    @Test
    void aRejectedTokenIsNotSaved() throws Exception {
        HostingerStubs.unauthorized(HOSTINGER, TOKEN);

        HttpResponse<String> response = save(TOKEN);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).contains("application/problem+json"));
        assertThat(detail(response)).startsWith("Hostinger rejected this token (HTTP 401)");
        assertThat(tokenStore.read(Source.HOSTINGER)).isEmpty();
        assertThat(hostinger()).isEqualTo("NOT_CONNECTED: null");
    }

    @Test
    void aRejectedReplacementKeepsTheOldTokenAndItsState() throws Exception {
        HostingerStubs.subscriptionsOk(HOSTINGER);
        save(TOKEN);
        HostingerStubs.unauthorized(HOSTINGER, OTHER_TOKEN);

        assertThat(save(OTHER_TOKEN).statusCode()).isEqualTo(422);

        assertThat(tokenStore.read(Source.HOSTINGER)).contains(TOKEN);
        assertThat(hostinger()).isEqualTo("CONNECTED: null");
    }

    @Test
    void hostingerUnreachableOrFailingMeansNothingIsSaved() throws Exception {
        HostingerStubs.serverError(HOSTINGER);
        HttpResponse<String> serverError = save(TOKEN);
        assertThat(serverError.statusCode()).isEqualTo(502);
        assertThat(detail(serverError)).startsWith("Could not reach Hostinger (it answered HTTP 500)");

        HostingerStubs.connectionReset(HOSTINGER);
        HttpResponse<String> reset = save(TOKEN);
        assertThat(reset.statusCode()).isEqualTo(502);
        assertThat(detail(reset)).startsWith("Could not reach Hostinger");

        assertThat(tokenStore.read(Source.HOSTINGER)).isEmpty();
    }

    @Test
    void rateLimitedMeansNothingIsSaved() throws Exception {
        HostingerStubs.rateLimited(HOSTINGER);

        HttpResponse<String> response = save(TOKEN);

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("30");
        assertThat(detail(response)).contains("rate limit");
        assertThat(tokenStore.read(Source.HOSTINGER)).isEmpty();
    }

    @Test
    void aBlankMissingOrOversizedTokenIsRefusedWithoutAskingHostinger() throws Exception {
        assertThat(save("   ").statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/api/hostinger/token", "{}").statusCode()).isEqualTo(400);
        assertThat(send("PUT", "/api/hostinger/token", "not json").statusCode()).isEqualTo(400);
        assertThat(save("x".repeat(HostingerController.MAX_TOKEN_LENGTH + 1)).statusCode()).isEqualTo(400);

        assertThat(subscriptionRequests()).isZero();
        assertThat(tokenStore.read(Source.HOSTINGER)).isEmpty();
    }

    @Test
    void aSavedTokenThatStopsWorkingIsAnErrorAndIsLoggedOnceWithTheTokenMasked() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.unauthorized(HOSTINGER, TOKEN);

        assertThat(hostinger()).isEqualTo("ERROR: " + HostingerStatusProvider.REVOKED);
        send("POST", "/api/hostinger/recheck", null);

        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(entry.get("level").asString()).isEqualTo("ERROR");
        assertThat(entry.get("action").asString()).isEqualTo("GET /api/billing/v1/subscriptions");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(401);
        assertThat(entry.get("context").get("response").asString()).contains("Unauthenticated.").contains("****");
    }

    @Test
    void rateLimitOrNetworkProblemsAreLimited() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);

        HostingerStubs.rateLimited(HOSTINGER);
        assertThat(hostinger()).startsWith("LIMITED: Hostinger asked us to slow down");
        clock.advanceSeconds(30); // Hostinger's Retry-After: 30 is over

        HostingerStubs.connectionReset(HOSTINGER);
        assertThat(recheck()).startsWith("LIMITED: Could not reach Hostinger");

        HostingerStubs.serverError(HOSTINGER);
        assertThat(recheck()).startsWith("LIMITED: Hostinger answered with an error (HTTP 500)");
    }

    @Test
    void theCheckIsCachedForAMinuteUnlessRechecked() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.subscriptionsOk(HOSTINGER);

        hostinger();
        hostinger();
        assertThat(subscriptionRequests()).isEqualTo(1);

        clock.advance(HostingerStatusProvider.CACHE_TTL.minusSeconds(1));
        hostinger();
        assertThat(subscriptionRequests()).isEqualTo(1);

        clock.advanceSeconds(1);
        hostinger();
        assertThat(subscriptionRequests()).isEqualTo(2);

        HostingerStubs.unauthorized(HOSTINGER, "nothing");
        assertThat(recheck()).startsWith("ERROR");
        assertThat(subscriptionRequests()).isEqualTo(3);
        assertThat(hostinger()).startsWith("ERROR");
        assertThat(subscriptionRequests()).isEqualTo(3);
    }

    @Test
    void deletingRemovesTheToken() throws Exception {
        HostingerStubs.subscriptionsOk(HOSTINGER);
        save(TOKEN);

        assertThat(send("DELETE", "/api/hostinger/token", null).statusCode()).isEqualTo(204);

        assertThat(tokenStore.read(Source.HOSTINGER)).isEmpty();
        assertThat(hostinger()).isEqualTo("NOT_CONNECTED: null");
        assertThat(send("DELETE", "/api/hostinger/token", null).statusCode()).as("again: nothing to do").isEqualTo(204);
    }

    @Test
    void theOpenApiDescriptionHasTheEndpoints() throws Exception {
        JsonNode paths = JSON.readTree(send("GET", "/v3/api-docs", null).body()).get("paths");

        assertThat(paths.get("/api/hostinger/token").get("put").get("operationId").asString()).isEqualTo("saveHostingerToken");
        assertThat(paths.get("/api/hostinger/token").get("delete").get("operationId").asString()).isEqualTo("deleteHostingerToken");
        assertThat(paths.get("/api/hostinger/recheck").get("post").get("operationId").asString()).isEqualTo("recheckHostinger");
    }

    private HttpResponse<String> save(String token) throws Exception {
        return send("PUT", "/api/hostinger/token", JSON.writeValueAsString(java.util.Map.of("token", token)));
    }

    private String recheck() throws Exception {
        return describe(JSON.readTree(send("POST", "/api/hostinger/recheck", null).body()));
    }

    private String hostinger() throws Exception {
        JsonNode hostinger = JSON.readTree(send("GET", "/api/status", null).body()).get("sources").get(1);
        assertThat(hostinger.get("source").asString()).isEqualTo("HOSTINGER");
        return describe(hostinger);
    }

    private static String describe(JsonNode status) {
        JsonNode message = status.get("message");
        return status.get("state").asString() + ": " + (message == null || message.isNull() ? null : message.asString());
    }

    private static String detail(HttpResponse<String> response) {
        return JSON.readTree(response.body()).get("detail").asString();
    }

    private int subscriptionRequests() {
        return HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.SUBSCRIPTIONS_PATH))).size();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        responses.add(response.body());
        return response;
    }
}
