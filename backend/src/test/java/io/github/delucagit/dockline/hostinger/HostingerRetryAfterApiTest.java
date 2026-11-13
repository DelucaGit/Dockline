package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
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
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 * Hostinger's own "too many requests" (429 with Retry-After), over real HTTP with Hostinger faked by WireMock.
 * Time is a {@link MutableClock} and waiting moves it forward ({@link ClockAdvancingWaiter}), so nothing sleeps:
 * the recorded waits and WireMock's call counts are the proof.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class HostingerRetryAfterApiTest {

    private static final String TOKEN = "hst_FakeRetryAfterToken0123456789abcdefXY";
    private static final String SITES = "/api/hostinger/sites";
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
    ClockAdvancingWaiter waiter;

    @Autowired
    TokenStore tokenStore;

    private final List<String> responses = new ArrayList<>();

    @BeforeEach
    void cleanStart() throws Exception {
        // Past the longest Retry-After (120 s), the request cap window and saved answers of earlier tests.
        clock.advanceSeconds(121);
        HOSTINGER.resetAll();
        send("DELETE", "/api/hostinger/token");
        send("DELETE", "/api/log");
        HostingerStubs.subscriptionsOk(HOSTINGER);
        siteNamed("one.example");
        tokenStore.save(Source.HOSTINGER, TOKEN);
        responses.clear();
        waiter.forget();
    }

    @AfterEach
    void theTokenNeverLeavesTheBackend(CapturedOutput output) throws Exception {
        responses.add(send("GET", "/api/log").body());
        for (String text : responses) {
            assertThat(text).doesNotContain(TOKEN);
        }
        assertThat(output.getAll()).doesNotContain(TOKEN);
        send("DELETE", "/api/hostinger/token");
    }

    @AfterAll
    static void stopFakeServer() {
        HOSTINGER.stop();
    }

    @Test
    void aShortRetryAfterIsWaitedOutAndTheRequestIsSentAgain() throws Exception {
        HostingerStubs.websitesRateLimitedOnce(HOSTINGER, "5");
        var start = clock.instant();

        HttpResponse<String> response = send("GET", SITES);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(domains(response)).containsExactly("one.example");
        assertThat(waiter.waits()).containsExactly(Duration.ofSeconds(5));
        assertThat(clock.instant()).isEqualTo(start.plusSeconds(5));
        assertThat(websiteCalls()).isEqualTo(2);
        assertThat(hostingerCalls()).as("nothing else was sent to Hostinger").isEqualTo(2);
        assertThat(JSON.readTree(response.body()).get("fetchedAt").asString())
                .isEqualTo(start.plusSeconds(5).toString());
        assertThat(hostinger().get("state").asString()).as("the wait is over").isEqualTo("CONNECTED");
    }

    @Test
    void theRetryAfterMayBeAnHttpDate() throws Exception {
        var at = clock.instant().truncatedTo(ChronoUnit.SECONDS).plusSeconds(8);
        HostingerStubs.websitesRateLimitedOnce(HOSTINGER,
                DateTimeFormatter.RFC_1123_DATE_TIME.format(at.atOffset(ZoneOffset.UTC)));

        assertThat(send("GET", SITES).statusCode()).isEqualTo(200);

        assertThat(waiter.waits()).hasSize(1);
        assertThat(clock.instant()).isEqualTo(at);
        assertThat(websiteCalls()).isEqualTo(2);
    }

    @Test
    void aMissingOrUnreadableRetryAfterMeansTheDefaultThirtySeconds() throws Exception {
        for (String retryAfter : new String[] {null, "soon", "-5"}) {
            clock.advanceSeconds(121);
            HOSTINGER.resetAll();
            siteNamed("one.example");
            HostingerStubs.websitesRateLimitedOnce(HOSTINGER, retryAfter);

            HttpResponse<String> response = send("GET", SITES);

            // 30 s is longer than a request may wait (10 s), so the answer is 429 at once with the countdown.
            assertRateLimited(response, 30);
            assertThat(websiteCalls()).as("Retry-After " + retryAfter).isEqualTo(1);
        }
        assertThat(waiter.waits()).isEmpty();
    }

    @Test
    void aLongWaitIsAnsweredAtOnceAndNoHostingerRequestIsSentUntilItIsOver() throws Exception {
        HostingerStubs.websitesRateLimited(HOSTINGER, 60);

        assertRateLimited(send("GET", SITES), 60);
        assertRateLimited(send("GET", SITES + "?refresh=true"), 60);
        clock.advanceSeconds(20);
        assertRateLimited(send("GET", SITES), 40);
        assertThat(send("POST", "/api/hostinger/recheck").statusCode()).isEqualTo(200);
        assertThat(send("PUT", "/api/hostinger/token", "{\"token\":\"" + TOKEN + "\"}").statusCode())
                .isEqualTo(429);

        assertThat(hostingerCalls()).as("only the first request reached Hostinger").isEqualTo(1);
        assertThat(waiter.waits()).isEmpty();

        siteNamed("two.example");
        clock.advanceSeconds(40);
        HttpResponse<String> after = send("GET", SITES);

        assertThat(after.statusCode()).isEqualTo(200);
        assertThat(domains(after)).containsExactly("two.example");
        assertThat(websiteCalls()).isEqualTo(2);
    }

    @Test
    void theStatusCountsDownWithoutAskingHostingerAndRecoversAfterTheWait() throws Exception {
        HostingerStubs.websitesRateLimited(HOSTINGER, 60);
        send("GET", SITES);

        JsonNode limited = hostinger();
        assertThat(limited.get("state").asString()).isEqualTo("LIMITED");
        assertThat(limited.get("retryAfterSeconds").asInt()).isEqualTo(60);
        assertThat(limited.get("message").asString()).isEqualTo("Hostinger asked us to slow down; retrying in 60 s.");

        clock.advanceSeconds(25);
        JsonNode later = hostinger();
        assertThat(later.get("retryAfterSeconds").asInt()).isEqualTo(35);
        assertThat(later.get("message").asString()).isEqualTo("Hostinger asked us to slow down; retrying in 35 s.");
        assertThat(subscriptionCalls()).isZero();

        clock.advanceSeconds(35);
        JsonNode recovered = hostinger();
        assertThat(recovered.get("state").asString()).isEqualTo("CONNECTED");
        assertThat(recovered.get("retryAfterSeconds").isNull()).isTrue();
        assertThat(subscriptionCalls()).as("the token is checked again once the wait is over").isEqualTo(1);
    }

    @Test
    void each429IsLoggedOnceAsAWarningWithTheTokenMasked() throws Exception {
        HostingerStubs.websitesRateLimited(HOSTINGER, 60);
        send("GET", SITES);
        send("GET", SITES);
        hostinger();

        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER").body());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("level").asString()).isEqualTo("WARN");
        assertThat(entry.get("action").asString()).isEqualTo("GET /api/hosting/v1/websites");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(429);
        assertThat(entry.get("context").get("request").asString()).contains("/api/hosting/v1/websites")
                .doesNotContain(TOKEN);
        assertThat(entry.get("context").get("response").asString()).contains("Too Many Attempts.");
    }

    @Test
    void retriesAreBoundedWhenHostingerKeepsSayingSlowDown() throws Exception {
        HostingerStubs.websitesRateLimited(HOSTINGER, 5);

        HttpResponse<String> response = send("GET", SITES);

        assertRateLimited(response, 5);
        assertThat(websiteCalls()).isEqualTo(HostingerClient.MAX_ATTEMPTS);
        assertThat(waiter.waits()).containsExactly(Duration.ofSeconds(5), Duration.ofSeconds(5));
        assertThat(JSON.readTree(send("GET", "/api/log?source=HOSTINGER").body())).hasSize(3);
    }

    private void assertRateLimited(HttpResponse<String> response, int seconds) throws Exception {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).hasValue(Integer.toString(seconds));
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("code").asString()).isEqualTo(HostingerSiteList.RATE_LIMITED);
        assertThat(body.get("retryAfterSeconds").asInt()).isEqualTo(seconds);
        assertThat(body.get("detail").asString()).isEqualTo("Hostinger asked us to slow down; retrying in " + seconds + " s.");
    }

    private void siteNamed(String domain) {
        HostingerStubs.websites(HOSTINGER, List.of(
                List.of(HostingerStubs.site(domain, "u1", 1, true, "2024-01-15T10:30:00+00:00"))));
    }

    private JsonNode hostinger() throws Exception {
        JsonNode hostinger = JSON.readTree(send("GET", "/api/status").body()).get("sources").get(1);
        assertThat(hostinger.get("source").asString()).isEqualTo("HOSTINGER");
        return hostinger;
    }

    private static List<String> domains(HttpResponse<String> response) throws Exception {
        return JSON.readTree(response.body()).get("sites").valueStream()
                .map(site -> site.get("domain").asString()).toList();
    }

    private static int websiteCalls() {
        return HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))).size();
    }

    private static int subscriptionCalls() {
        return HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.SUBSCRIPTIONS_PATH))).size();
    }

    private static int hostingerCalls() {
        return HOSTINGER.findAll(anyRequestedFor(anyUrl())).size();
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return send(method, path, null);
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
