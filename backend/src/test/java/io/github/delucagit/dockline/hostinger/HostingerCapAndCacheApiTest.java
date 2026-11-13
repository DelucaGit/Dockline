package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.delucagit.dockline.ClockAdvancingWaiter;
import io.github.delucagit.dockline.MutableClock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Dockline's Hostinger request cap (50 per minute across all features, extra requests wait) and 60-second cache,
 * over real HTTP with Hostinger faked by WireMock. Time is a {@link MutableClock}; waiting moves it forward
 * ({@link ClockAdvancingWaiter}), so nothing sleeps. The proof is how many calls WireMock receives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HostingerCapAndCacheApiTest {

    private static final String TOKEN = "hst_FakeCapCacheToken0123456789abcdefXYZ";
    private static final String OTHER_TOKEN = "hst_OtherCapCacheToken0123456789abcdefXY";
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

    @BeforeEach
    void cleanStart() throws Exception {
        clock.advanceSeconds(61);
        send("DELETE", "/api/hostinger/token", null);
        HOSTINGER.resetAll();
        HostingerStubs.subscriptionsOk(HOSTINGER);
        siteNamed("one.example");
        waiter.forget();
    }

    @AfterEach
    void removeToken() throws Exception {
        send("DELETE", "/api/hostinger/token", null);
    }

    @AfterAll
    static void stopFakeServer() {
        HOSTINGER.stop();
    }

    @Test
    void theFiftyFirstRequestInAMinuteWaitsItsTurnAcrossAllFeatures() throws Exception {
        connect(TOKEN);
        clock.advanceSeconds(61); // the token check above is out of the window again
        Instant start = clock.instant();

        for (int i = 0; i < 25; i++) {
            assertThat(send("POST", "/api/hostinger/recheck", null).statusCode()).isEqualTo(200);
            assertThat(send("GET", SITES + "?refresh=true", null).statusCode()).isEqualTo(200);
        }

        assertThat(hostingerCalls()).isEqualTo(1 + 50);
        assertThat(waiter.waits()).isEmpty();
        assertThat(clock.instant()).isEqualTo(start);

        HttpResponse<String> fiftyFirst = send("GET", SITES + "?refresh=true", null);

        assertThat(fiftyFirst.statusCode()).isEqualTo(200);
        assertThat(waiter.waits()).containsExactly(Duration.ofSeconds(60));
        assertThat(clock.instant()).isEqualTo(start.plusSeconds(60));
        assertThat(JSON.readTree(fiftyFirst.body()).get("fetchedAt").asString())
                .isEqualTo(start.plusSeconds(60).toString());
        assertThat(hostingerCalls()).isEqualTo(1 + 51);
    }

    @Test
    void aSecondRequestWithinAMinuteIsAnsweredFromMemoryAndExpiresAfterSixtySeconds() throws Exception {
        connect(TOKEN);
        Instant first = clock.instant();
        JsonNode one = sites(SITES);

        clock.advanceSeconds(59);
        siteNamed("two.example");
        JsonNode again = sites(SITES);

        assertThat(websiteCalls()).isEqualTo(1);
        assertThat(again).isEqualTo(one);
        assertThat(again.get("fetchedAt").asString()).isEqualTo(first.toString());
        assertThat(domains(again)).containsExactly("one.example");

        clock.advanceSeconds(1);
        JsonNode expired = sites(SITES);

        assertThat(websiteCalls()).isEqualTo(2);
        assertThat(expired.get("fetchedAt").asString()).isEqualTo(first.plusSeconds(60).toString());
        assertThat(domains(expired)).containsExactly("two.example");
    }

    @Test
    void refreshSkipsTheSavedCopyAndSavesTheNewAnswer() throws Exception {
        connect(TOKEN);
        sites(SITES);

        clock.advanceSeconds(10);
        Instant refreshedAt = clock.instant();
        siteNamed("two.example");
        JsonNode refreshed = sites(SITES + "?refresh=true");

        assertThat(websiteCalls()).isEqualTo(2);
        assertThat(domains(refreshed)).containsExactly("two.example");

        clock.advanceSeconds(10);
        siteNamed("three.example");
        JsonNode reused = sites(SITES);

        assertThat(websiteCalls()).isEqualTo(2);
        assertThat(domains(reused)).containsExactly("two.example");
        assertThat(reused.get("fetchedAt").asString()).isEqualTo(refreshedAt.toString());
    }

    @Test
    void errorsAreNeverSaved() throws Exception {
        connect(TOKEN);
        HostingerStubs.websitesServerError(HOSTINGER);
        assertThat(send("GET", SITES, null).statusCode()).isEqualTo(502);

        siteNamed("one.example");
        JsonNode recovered = sites(SITES);

        assertThat(websiteCalls()).isEqualTo(2);
        assertThat(domains(recovered)).containsExactly("one.example");
    }

    @Test
    void checkingATokenAlwaysAsksHostinger() throws Exception {
        connect(TOKEN);
        connect(TOKEN);
        send("POST", "/api/hostinger/recheck", null);
        send("POST", "/api/hostinger/recheck", null);

        HOSTINGER.verify(4, getRequestedFor(urlPathEqualTo(HostingerStubs.SUBSCRIPTIONS_PATH)));
    }

    @Test
    void removingOrReplacingTheTokenForgetsSavedAnswers() throws Exception {
        connect(TOKEN);
        sites(SITES);

        send("DELETE", "/api/hostinger/token", null);
        connect(TOKEN);
        sites(SITES);
        assertThat(websiteCalls()).isEqualTo(2);

        connect(OTHER_TOKEN);
        sites(SITES);
        assertThat(websiteCalls()).isEqualTo(3);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))).get(2)
                .getHeader("Authorization")).isEqualTo("Bearer " + OTHER_TOKEN);
    }

    private void siteNamed(String domain) {
        HostingerStubs.websites(HOSTINGER, List.of(
                List.of(HostingerStubs.site(domain, "u1", 1, true, "2024-01-15T10:30:00+00:00"))));
    }

    private void connect(String token) throws Exception {
        assertThat(send("PUT", "/api/hostinger/token", "{\"token\":\"" + token + "\"}").statusCode()).isEqualTo(200);
    }

    private JsonNode sites(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, null);
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private static List<String> domains(JsonNode body) {
        return body.get("sites").valueStream().map(site -> site.get("domain").asString()).toList();
    }

    private static int websiteCalls() {
        return HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))).size();
    }

    private static int hostingerCalls() {
        return HOSTINGER.findAll(anyRequestedFor(anyUrl())).size();
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(json));
        }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
