package io.github.delucagit.dockline.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The Log REST API over real HTTP, with the test endpoints switched on to report problems. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "dockline.test-endpoints=true")
class LogApiTest {

    private static final String TOKEN = TestEndpoints.FAKE_TOKEN;
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    @AfterEach
    void clearLog() throws Exception {
        assertThat(send("DELETE", "/api/log").statusCode()).isEqualTo(204);
    }

    @Test
    void listsReportedProblemsNewestFirstWithAllFields() throws Exception {
        send("POST", "/api/test/report-error?level=WARN&source=HOSTINGER&action=GET%20/api/vps&statusCode=429&message=Slow%20down");
        send("POST", "/api/test/report-error");

        HttpResponse<String> response = send("GET", "/api/log");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode entries = JSON.readTree(response.body());
        assertThat(entries).hasSize(2);
        JsonNode newest = entries.get(0);
        assertThat(newest.get("level").asString()).isEqualTo("ERROR");
        assertThat(newest.get("source").asString()).isEqualTo("GITHUB");
        assertThat(newest.get("id").asLong()).isGreaterThan(entries.get(1).get("id").asLong());

        JsonNode older = entries.get(1);
        assertThat(older.get("level").asString()).isEqualTo("WARN");
        assertThat(older.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(older.get("action").asString()).isEqualTo("GET /api/vps");
        assertThat(older.get("statusCode").asInt()).isEqualTo(429);
        assertThat(older.get("message").asString()).startsWith("Slow down");
        assertThat(older.get("time").asString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z");
        JsonNode context = older.get("context");
        assertThat(context.get("appVersion").asString()).isNotBlank();
        assertThat(context.get("request").asString()).contains("GET /api/vps");
        assertThat(context.get("response").asString()).contains("Bad credentials");
        assertThat(context.get("stackTrace").asString()).contains("IllegalStateException").contains("\tat ");
    }

    @Test
    void filtersBySourceAndLevel() throws Exception {
        send("POST", "/api/test/report-error?level=WARN&source=HOSTINGER");
        send("POST", "/api/test/report-error?level=ERROR&source=HOSTINGER");
        send("POST", "/api/test/report-error?level=ERROR&source=GITHUB");

        assertThat(JSON.readTree(send("GET", "/api/log?source=HOSTINGER").body())).hasSize(2)
                .allSatisfy(e -> assertThat(e.get("source").asString()).isEqualTo("HOSTINGER"));
        assertThat(JSON.readTree(send("GET", "/api/log?level=ERROR").body())).hasSize(2)
                .allSatisfy(e -> assertThat(e.get("level").asString()).isEqualTo("ERROR"));
        assertThat(JSON.readTree(send("GET", "/api/log?source=HOSTINGER&level=WARN").body())).hasSize(1);
        assertThat(JSON.readTree(send("GET", "/api/log?source=BACKEND").body())).isEmpty();
        assertThat(send("GET", "/api/log?level=INFO").statusCode()).isEqualTo(400);
    }

    @Test
    void clearEmptiesTheList() throws Exception {
        send("POST", "/api/test/report-error");

        assertThat(send("DELETE", "/api/log").statusCode()).isEqualTo(204);

        assertThat(JSON.readTree(send("GET", "/api/log").body())).isEmpty();
    }

    @Test
    void tokensAreMaskedInApiResponses() throws Exception {
        String saved = "hostinger-api-token-" + "9f8e7d6c5b4a";
        SecretMasker.register(saved);
        try {
            HttpResponse<String> reported = send("POST", "/api/test/report-error?message=Rejected%20" + saved);
            String listed = send("GET", "/api/log").body();

            for (String body : new String[] {reported.body(), listed}) {
                assertThat(body).doesNotContain(TOKEN).doesNotContain("0123456789abcdef").doesNotContain(saved);
                assertThat(body).contains("ghp_" + SecretMasker.MASK).contains("Authorization: " + SecretMasker.MASK);
            }
        } finally {
            SecretMasker.unregister(saved);
        }
    }

    @Test
    void anUncaughtExceptionBecomesAnErrorEntryWithoutLeakingTheToken() throws Exception {
        HttpResponse<String> failed = send("POST", "/api/test/fail");

        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(failed.body()).doesNotContain(TOKEN).contains("Simulated unexpected failure");

        JsonNode entries = JSON.readTree(send("GET", "/api/log").body());
        assertThat(entries).hasSize(1);
        JsonNode entry = entries.get(0);
        assertThat(entry.get("level").asString()).isEqualTo("ERROR");
        assertThat(entry.get("source").asString()).isEqualTo("BACKEND");
        assertThat(entry.get("action").asString()).isEqualTo("POST /api/test/fail");
        assertThat(entry.get("statusCode").asInt()).isEqualTo(500);
        assertThat(entry.get("context").get("stackTrace").asString()).contains("IllegalStateException");
        assertThat(entry.toString()).doesNotContain(TOKEN);
    }

    @Test
    void theLogApiIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs").body();

        assertThat(docs).contains("\"/api/log\"").contains("listLogEntries").contains("clearLogEntries")
                .contains("\"LogEntry\"").doesNotContain("/api/test");
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
