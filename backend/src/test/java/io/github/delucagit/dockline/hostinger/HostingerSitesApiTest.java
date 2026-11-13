package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.github.delucagit.dockline.ClockAdvancingWaiter;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
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
 * {@code GET /api/hostinger/sites} over real HTTP, with Hostinger faked by WireMock and time driven by a
 * {@link MutableClock}. Every response and every log line is collected and checked for the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class HostingerSitesApiTest {

    private static final String TOKEN = "hst_FakeSiteListToken0123456789abcdefXYZ";
    private static final String PATH = "/api/hostinger/sites";
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
        send("DELETE", "/api/hostinger/token");
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
        send("DELETE", "/api/hostinger/token");
    }

    @AfterAll
    static void stopFakeServer() {
        HOSTINGER.stop();
    }

    @Test
    void allPagesAreFetchedWithAllFieldsSortedByDomain() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websites(HOSTINGER, List.of(
                List.of(HostingerStubs.site("zeta.example", "u100", 111, true, "2024-01-15T10:30:00+00:00"),
                        HostingerStubs.site("Beta.example", "u100", 111, false, "2025-06-01T08:00:00+02:00")),
                List.of(HostingerStubs.site("alpha.example", "u200", 222, true, "2023-03-03T03:03:03Z"),
                        "{\"domain\":null,\"is_enabled\":true,\"username\":null,\"order_id\":333,"
                                + "\"created_at\":\"2026-01-01T00:00:00+00:00\",\"website_type\":\"horizons\"}"),
                List.of(HostingerStubs.site("middle.example", "u200", 222, true, "2024-05-05T05:05:05+00:00"))));

        HttpResponse<String> response = send("GET", PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(3);
        assertThat(body.get("fetchedAt").asString()).isEqualTo(clock.instant().toString());
        assertThat(body.get("truncated").asBoolean()).isFalse();
        JsonNode sites = body.get("sites");
        assertThat(sites.valueStream().map(s -> s.get("domain").isNull() ? null : s.get("domain").asString()))
                .containsExactly("alpha.example", "Beta.example", "middle.example", "zeta.example", null);
        JsonNode beta = sites.get(1);
        assertThat(beta.properties()).hasSize(7);
        assertThat(beta.get("username").asString()).isEqualTo("u100");
        assertThat(beta.get("orderId").asLong()).isEqualTo(111);
        assertThat(beta.get("websiteType").asString()).isEqualTo("wordpress");
        assertThat(beta.get("rootDirectory").asString()).isEqualTo("/home/u100/domains/Beta.example/public_html");
        assertThat(beta.get("enabled").asBoolean()).isFalse();
        assertThat(beta.get("createdAt").asString()).isEqualTo("2025-06-01T06:00:00Z");
        JsonNode noDomain = sites.get(4);
        assertThat(noDomain.get("username").isNull()).isTrue();
        assertThat(noDomain.get("rootDirectory").isNull()).isTrue();
        assertThat(noDomain.get("websiteType").asString()).isEqualTo("horizons");

        List<LoggedRequest> requests = HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)));
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).getUrl()).isEqualTo(HostingerStubs.WEBSITES_PATH + "?page=1&per_page=100");
        assertThat(requests.get(1).queryParameter("page").firstValue()).isEqualTo("2");
        assertThat(requests.get(2).queryParameter("page").firstValue()).isEqualTo("3");
        HOSTINGER.verify(3, getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void aRefreshAsksHostingerAgainAndFetchedAtFollowsTheClock() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websites(HOSTINGER, List.of(
                List.of(HostingerStubs.site("one.example", "u1", 1, true, "2024-01-15T10:30:00+00:00"))));

        send("GET", PATH);
        clock.advanceSeconds(5);
        Instant now = clock.instant();
        JsonNode second = JSON.readTree(send("GET", PATH + "?refresh=true").body());

        assertThat(second.get("fetchedAt").asString()).isEqualTo(now.toString());
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)))).hasSize(2);
    }

    @Test
    void anEmptyPlanIsAnEmptyList() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websites(HOSTINGER, List.of(List.of()));

        HttpResponse<String> response = send("GET", PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("sites")).isEmpty();
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH)))).hasSize(1);
    }

    @Test
    void notConnectedIsItsOwnProblemAndHostingerIsNotCalled() throws Exception {
        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 409, HostingerSiteList.NOT_CONNECTED);
        assertThat(JSON.readTree(response.body()).get("detail").asString()).contains("Connect to Hostinger in Settings");
        assertThat(HOSTINGER.findAll(anyRequestedFor(anyUrl()))).isEmpty();
        assertThat(JSON.readTree(send("GET", "/api/log").body())).isEmpty();
    }

    @Test
    void aRevokedTokenIsReportedTurnsTheStatusRedAndIsLoggedMasked() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websitesUnauthorized(HOSTINGER, TOKEN);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 409, HostingerSiteList.TOKEN_REJECTED);
        assertThat(hostinger()).isEqualTo("ERROR: " + HostingerStatusProvider.REVOKED);
        // The status came from the website call; the subscriptions check was not needed.
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.SUBSCRIPTIONS_PATH)))).isEmpty();
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("level").asString()).isEqualTo("ERROR");
        assertThat(entry.get("action").asString()).isEqualTo("GET /api/hosting/v1/websites");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(401);
        assertThat(entry.get("context").get("request").asString()).contains("/api/hosting/v1/websites")
                .contains("with the saved token");
        assertThat(entry.get("context").get("response").asString()).contains("Unauthenticated.");
    }

    @Test
    void aRateLimitPassesHostingersRetryAfterOn() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websitesRateLimited(HOSTINGER, 42);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 429, HostingerSiteList.RATE_LIMITED);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("42");
        assertThat(hostinger()).startsWith("LIMITED: Hostinger's rate limit is reached");
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("level").asString()).isEqualTo("WARN");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(429);
    }

    @Test
    void aHostingerServerErrorIsUnavailable() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websitesServerError(HOSTINGER);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(hostinger()).isEqualTo("LIMITED: Hostinger answered with an error (HTTP 500). Try again later.");
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("statusCode").asInt()).isEqualTo(500);
        assertThat(entry.get("context").get("response").asString()).contains("Server Error");
    }

    @Test
    void anUnreachableHostingerIsUnavailable() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websitesConnectionReset(HOSTINGER);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(hostinger()).startsWith("LIMITED: Could not reach Hostinger");
        assertThat(onlyLogEntry().get("context").get("stackTrace").asString()).isNotBlank();
    }

    @Test
    void anUnreadableAnswerIsUnavailable() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HOSTINGER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("[\"not\",\"a page\"]")));

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(hostinger()).isEqualTo("LIMITED: Hostinger sent an answer Dockline could not read.");
        assertThat(onlyLogEntry().get("message").asString()).contains("could not be read");
    }

    @Test
    void theListStopsAfterTheMaximumPagesAndSaysSo() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.websites(HOSTINGER, IntStream.rangeClosed(1, HostingerSiteList.MAX_PAGES + 1)
                .mapToObj(page -> List.of(HostingerStubs.site("site-" + page + ".example", "u1", 1, true,
                        "2024-01-15T10:30:00+00:00")))
                .toList());

        JsonNode body = JSON.readTree(send("GET", PATH).body());

        assertThat(body.get("truncated").asBoolean()).isTrue();
        assertThat(body.get("sites")).hasSize(HostingerSiteList.MAX_PAGES);
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))))
                .hasSize(HostingerSiteList.MAX_PAGES);
        assertThat(onlyLogEntry().get("level").asString()).isEqualTo("WARN");
    }

    @Test
    void theEndpointIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs").body();

        assertThat(docs).contains("\"/api/hostinger/sites\"", "\"listHostingerSites\"", "\"HostingerSites\"",
                "\"HostingerSite\"", "HOSTINGER_NOT_CONNECTED", "HOSTINGER_TOKEN_REJECTED", "HOSTINGER_RATE_LIMITED",
                "HOSTINGER_UNAVAILABLE");
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
        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER").body());
        assertThat(entries).hasSize(1);
        return entries.get(0);
    }

    /** "STATE: message" of HOSTINGER in GET /api/status. */
    private String hostinger() throws Exception {
        JsonNode hostinger = JSON.readTree(send("GET", "/api/status").body()).get("sources").get(1);
        assertThat(hostinger.get("source").asString()).isEqualTo("HOSTINGER");
        JsonNode message = hostinger.get("message");
        return hostinger.get("state").asString() + ": " + (message == null || message.isNull() ? null : message.asString());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        responses.add(response.body());
        return response;
    }
}
