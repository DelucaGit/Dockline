package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import io.github.delucagit.dockline.ClockAdvancingWaiter;
import io.github.delucagit.dockline.MutableClock;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
 * A slow Hostinger (issue #107): an answer slower than the read timeout ({@code dockline.hostinger.read-timeout},
 * 2 s here instead of 30 s) is reported as "took too long", not as a connection problem; an answer that is slow but
 * within the timeout is used as normal; a Hostinger that cannot be reached still says "check your internet
 * connection".
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HostingerTimeoutApiTest {

    private static final String TOKEN = "hst_FakeTimeoutToken0123456789abcdefghijXY";
    private static final String USER = "u100";
    private static final String SHA = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0";
    private static final int TIMEOUT_MS = 2000;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final WireMockServer HOSTINGER = HostingerStubs.start();
    private static final String BUILDS_PATHS = "/api/hosting/v1/accounts/.*/nodejs/builds";
    private static final String TOOK_TOO_LONG = "Hostinger took too long to answer. Dockline will try again.";

    @DynamicPropertySource
    static void pointAtFakes(DynamicPropertyRegistry registry) {
        registry.add("dockline.hostinger.api-url", HOSTINGER::baseUrl);
        registry.add("dockline.hostinger.read-timeout", () -> TIMEOUT_MS + "ms");
        registry.add("dockline.settings.dir",
                () -> Path.of("target", "test-settings", "timeout-" + UUID.randomUUID()).toAbsolutePath().toString());
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

    @BeforeEach
    void cleanStart() throws Exception {
        clock.advanceSeconds(121);
        HOSTINGER.resetAll();
        HOSTINGER.setGlobalFixedDelay(0);
        send("DELETE", "/api/hostinger/token", null);
        send("DELETE", "/api/log", null);
        tokenStore.save(Source.HOSTINGER, TOKEN);
    }

    @AfterEach
    void forgetTheToken() throws Exception {
        HOSTINGER.setGlobalFixedDelay(0);
        send("DELETE", "/api/hostinger/token", null);
    }

    @AfterAll
    static void stopFakeServer() {
        HOSTINGER.stop();
    }

    @Test
    void aSlowBuildsAnswerIsReportedAsTookTooLong() throws Exception {
        sites(HostingerStubs.site("app.example", USER, 111, true, "2024-01-15T10:30:00+00:00", "nodejs"));
        HOSTINGER.stubFor(get(urlPathMatching(BUILDS_PATHS))
                .willReturn(aResponse().withStatus(200).withFixedDelay(TIMEOUT_MS + 1500)));

        HttpResponse<String> response = send("GET", "/api/sites/deploys", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode deploy = JSON.readTree(response.body()).get("deploys").get(0);
        assertThat(deploy.get("status").asString()).isEqualTo("UNKNOWN");
        JsonNode problem = deploy.get("problems").get(0);
        assertThat(problem.get("code").asString()).isEqualTo(HostingerSiteList.UNAVAILABLE);
        assertThat(problem.get("message").asString()).contains("took too long").doesNotContain("internet connection");

        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body());
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).get("level").asString()).isEqualTo("WARN");
        assertThat(entries.get(0).get("action").asString())
                .isEqualTo("GET " + HostingerStubs.nodejsBuildsPath(USER, "app.example"));
        assertThat(entries.get(0).get("message").asString())
                .contains("Hostinger took too long to answer (more than 2 s)");
    }

    @Test
    void aSlowWebsiteListIsReportedAsTookTooLong() throws Exception {
        HOSTINGER.stubFor(get(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))
                .willReturn(aResponse().withStatus(200).withFixedDelay(TIMEOUT_MS + 1500)));

        HttpResponse<String> response = send("GET", "/api/hostinger/sites", null);

        JsonNode problem = assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(problem.get("detail").asString()).isEqualTo(TOOK_TOO_LONG);
        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body());
        assertThat(entries.get(0).get("level").asString()).isEqualTo("WARN");
        assertThat(entries.get(0).get("message").asString()).contains("took too long");
    }

    @Test
    void anAnswerThatIsSlowButWithinTheTimeoutIsUsed() throws Exception {
        sites(HostingerStubs.site("app.example", USER, 111, true, "2024-01-15T10:30:00+00:00", "nodejs"));
        HostingerStubs.buildSucceeded(HOSTINGER, USER, "app.example", SHA, "Initial commit");
        HOSTINGER.setGlobalFixedDelay(TIMEOUT_MS / 2);

        HttpResponse<String> response = send("GET", "/api/sites/deploys", null);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode deploy = JSON.readTree(response.body()).get("deploys").get(0);
        assertThat(deploy.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(deploy.get("problems")).isEmpty();
        assertThat(JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body())).isEmpty();
    }

    @Test
    void anUnreachableHostingerStillSaysCheckYourInternetConnection() throws Exception {
        HOSTINGER.stubFor(get(urlPathEqualTo(HostingerStubs.WEBSITES_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        HttpResponse<String> response = send("GET", "/api/hostinger/sites", null);

        JsonNode problem = assertProblem(response, 502, HostingerSiteList.UNAVAILABLE);
        assertThat(problem.get("detail").asString())
                .isEqualTo("Could not reach Hostinger. Check your internet connection and try again.");
        JsonNode entries = JSON.readTree(send("GET", "/api/log?source=HOSTINGER", null).body());
        assertThat(entries.get(0).get("message").asString()).startsWith("Could not reach Hostinger: ");
    }

    @Test
    void onlyAReadTimeoutCountsAsTookTooLong() {
        assertThat(HostingerTimeoutException.isReadTimeout(
                new RuntimeException(new java.net.http.HttpTimeoutException("Request cancelled")))).isTrue();
        assertThat(HostingerTimeoutException.isReadTimeout(
                new RuntimeException(new java.net.http.HttpConnectTimeoutException("HTTP connect timed out")))).isFalse();
        assertThat(HostingerTimeoutException.isReadTimeout(
                new RuntimeException(new java.net.ConnectException("Connection refused")))).isFalse();
    }

    /** The website list: these websites, on one page. */
    private static void sites(String... sites) {
        HostingerStubs.websites(HOSTINGER, List.of(List.of(sites)));
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("code").asString()).isEqualTo(code);
        return problem;
    }

    private HttpResponse<String> send(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        request.method(method, HttpRequest.BodyPublishers.noBody());
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.body()).doesNotContain(TOKEN);
        return response;
    }
}
