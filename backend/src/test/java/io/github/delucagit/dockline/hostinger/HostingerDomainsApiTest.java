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
 * {@code GET /api/hostinger/domains} over real HTTP, with Hostinger faked by WireMock and time driven by a
 * {@link MutableClock}. Every response and every log line is collected and checked for the token.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class HostingerDomainsApiTest {

    private static final String TOKEN = "hst_FakeDomainListToken0123456789abcdefXYZ";
    private static final String PATH = "/api/hostinger/domains";
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
    void domainsAreSortedByEarliestExpiryThenNameWithAllFields() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.domains(HOSTINGER, List.of(
                HostingerStubs.domain(1, "late.example", "active", "2028-01-01T00:00:00Z"),
                HostingerStubs.domain(2, "zzz-noexpiry.example", "active", null),
                HostingerStubs.domain(3, "Soon.example", "active", "2026-12-01T10:00:00+02:00"),
                HostingerStubs.domain(4, "aaa-noexpiry.example", "pending_setup", null),
                HostingerStubs.domain(5, null, "requested", null),
                HostingerStubs.domain(6, "tie-b.example", "expired", "2027-05-05T05:05:05Z"),
                HostingerStubs.domain(7, "tie-a.example", "active", "2027-05-05T05:05:05Z")));

        HttpResponse<String> response = send("GET", PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.properties()).hasSize(2);
        assertThat(body.get("fetchedAt").asString()).isEqualTo(clock.instant().toString());
        JsonNode domains = body.get("domains");
        assertThat(domains.valueStream().map(d -> d.get("domain").isNull() ? null : d.get("domain").asString()))
                .containsExactly("Soon.example", "tie-a.example", "tie-b.example", "late.example",
                        "aaa-noexpiry.example", "zzz-noexpiry.example", null);
        JsonNode soon = domains.get(0);
        assertThat(soon.properties()).hasSize(5);
        assertThat(soon.get("expiresAt").asString()).isEqualTo("2026-12-01T08:00:00Z");
        assertThat(soon.get("status").asString()).isEqualTo("active");
        assertThat(soon.get("type").asString()).isEqualTo("domain");
        assertThat(soon.get("createdAt").asString()).isEqualTo("2025-02-27T11:54:22Z");
        assertThat(domains.get(4).get("expiresAt").isNull()).isTrue();
        assertThat(domains.get(4).get("status").asString()).isEqualTo("pending_setup");
        HOSTINGER.verify(1, getRequestedFor(urlPathEqualTo(HostingerStubs.DOMAINS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void theSecondCallUsesTheSavedCopyAndARefreshAsksHostingerAgain() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.domains(HOSTINGER, List.of(HostingerStubs.domain(1, "one.example", "active", "2027-01-01T00:00:00Z")));

        JsonNode first = JSON.readTree(send("GET", PATH).body());
        clock.advanceSeconds(5);
        JsonNode cached = JSON.readTree(send("GET", PATH).body());
        assertThat(cached.get("fetchedAt")).isEqualTo(first.get("fetchedAt"));
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.DOMAINS_PATH)))).hasSize(1);

        Instant now = clock.instant();
        JsonNode refreshed = JSON.readTree(send("GET", PATH + "?refresh=true").body());

        assertThat(refreshed.get("fetchedAt").asString()).isEqualTo(now.toString());
        assertThat(HOSTINGER.findAll(getRequestedFor(urlPathEqualTo(HostingerStubs.DOMAINS_PATH)))).hasSize(2);
    }

    @Test
    void noDomainsIsAnEmptyList() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.domains(HOSTINGER, List.of());

        HttpResponse<String> response = send("GET", PATH);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("domains")).isEmpty();
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
        HostingerStubs.domainsUnauthorized(HOSTINGER, TOKEN);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 409, HostingerSiteList.TOKEN_REJECTED);
        assertThat(hostinger()).isEqualTo("ERROR: " + HostingerStatusProvider.REVOKED);
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("level").asString()).isEqualTo("ERROR");
        assertThat(entry.get("action").asString()).isEqualTo("GET /api/domains/v1/portfolio");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(401);
        assertThat(entry.get("context").get("request").asString()).contains("/api/domains/v1/portfolio")
                .contains("with the saved token");
        assertThat(entry.get("context").get("response").asString()).contains("Unauthenticated.");
    }

    @Test
    void aRateLimitPassesHostingersRetryAfterOn() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.domainsRateLimited(HOSTINGER, 42);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 429, HostingerSiteList.RATE_LIMITED);
        assertThat(response.headers().firstValue("Retry-After")).hasValue("42");
        assertThat(JSON.readTree(response.body()).get("retryAfterSeconds").asInt()).isEqualTo(42);
        assertThat(hostinger()).isEqualTo("LIMITED: Hostinger asked us to slow down; retrying in 42 s.");
        JsonNode entry = onlyLogEntry();
        assertThat(entry.get("level").asString()).isEqualTo("WARN");
        assertThat(entry.get("action").asString()).isEqualTo("GET /api/domains/v1/portfolio");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(429);
    }

    @Test
    void aHostingerServerErrorIsUnavailable() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HostingerStubs.domainsServerError(HOSTINGER);

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
        HostingerStubs.domainsConnectionReset(HOSTINGER);

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(hostinger()).startsWith("LIMITED: Could not reach Hostinger");
        assertThat(onlyLogEntry().get("context").get("stackTrace").asString()).isNotBlank();
    }

    @Test
    void anUnreadableAnswerIsUnavailable() throws Exception {
        tokenStore.save(Source.HOSTINGER, TOKEN);
        HOSTINGER.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathEqualTo(HostingerStubs.DOMAINS_PATH))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{\"data\":[]}")));

        HttpResponse<String> response = send("GET", PATH);

        assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(hostinger()).isEqualTo("LIMITED: Hostinger sent an answer Dockline could not read.");
        assertThat(onlyLogEntry().get("message").asString()).contains("could not be read");
    }

    @Test
    void theEndpointIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs").body();

        assertThat(docs).contains("\"/api/hostinger/domains\"", "\"listHostingerDomains\"", "\"HostingerDomains\"",
                "\"HostingerDomain\"", "HOSTINGER_NOT_CONNECTED", "HOSTINGER_TOKEN_REJECTED", "HOSTINGER_RATE_LIMITED",
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
