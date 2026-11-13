package io.github.delucagit.dockline.token;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The real app with the in-memory token store (as in every test, set by the build): tokens are saved,
 * read and deleted through the app's {@link TokenStore}, and the effect is checked over real HTTP.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // GitHub and Hostinger calls go nowhere (nothing listens on port 9), never to the real APIs.
        properties = {"dockline.test-endpoints=true", "dockline.github.api-url=http://127.0.0.1:9",
                "dockline.hostinger.api-url=http://127.0.0.1:9"})
@ExtendWith(OutputCaptureExtension.class)
class TokenStoreAndStatusApiTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @Autowired
    TokenStore tokenStore;

    @AfterEach
    void forgetTokens() throws Exception {
        for (Source source : Source.values()) {
            tokenStore.delete(source);
        }
        send("DELETE", "/api/log");
    }

    @Test
    void usesTheInMemoryStoreInTests() {
        assertThat(tokenStore).isInstanceOf(MaskingTokenStore.class);
        assertThat(tokenStore).extracting("delegate").isInstanceOf(InMemoryTokenStore.class);
    }

    @Test
    void savesReadsAndDeletesATokenPerSource() {
        assertThat(tokenStore.read(Source.GITHUB)).isEmpty();

        tokenStore.save(Source.GITHUB, "gho_savedInTheTest0123456789");
        tokenStore.save(Source.HOSTINGER, "hostinger-saved-in-the-test");
        tokenStore.save(Source.GITHUB, "gho_replacedInTheTest987654");

        assertThat(tokenStore.read(Source.GITHUB)).contains("gho_replacedInTheTest987654");
        assertThat(tokenStore.read(Source.HOSTINGER)).contains("hostinger-saved-in-the-test");

        tokenStore.delete(Source.GITHUB);

        assertThat(tokenStore.read(Source.GITHUB)).isEmpty();
        assertThat(tokenStore.read(Source.HOSTINGER)).contains("hostinger-saved-in-the-test");
    }

    @Test
    void theStatusFollowsTheStoredToken() throws Exception {
        // Both sources have their own status provider that checks a stored token with the API
        // (see GithubConnectApiTest, HostingerTokenApiTest); here the API cannot be reached, so LIMITED.
        assertThat(states()).isEqualTo("GITHUB=NOT_CONNECTED,HOSTINGER=NOT_CONNECTED");

        tokenStore.save(Source.HOSTINGER, "hostinger-status-test-token");
        assertThat(states()).isEqualTo("GITHUB=NOT_CONNECTED,HOSTINGER=LIMITED");

        tokenStore.delete(Source.HOSTINGER);
        assertThat(states()).isEqualTo("GITHUB=NOT_CONNECTED,HOSTINGER=NOT_CONNECTED");
    }

    @Test
    void theStatusResponseNeverContainsTheToken() throws Exception {
        tokenStore.save(Source.HOSTINGER, "hostinger-never-in-status-42");

        HttpResponse<String> response = send("GET", "/api/status");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain("hostinger-never-in-status-42");
        JsonNode hostinger = JSON.readTree(response.body()).get("sources").get(1);
        assertThat(hostinger.get("source").asString()).isEqualTo("HOSTINGER");
        assertThat(hostinger.get("state").asString()).isEqualTo("LIMITED");
    }

    @Test
    void aSavedTokenIsMaskedInLogsAndInTheLogApi(CapturedOutput output) throws Exception {
        // Not shaped like any known token, so only the registration on save can mask it.
        String token = "plain-saved-token-" + "a1b2c3d4e5";
        tokenStore.save(Source.HOSTINGER, token);

        HttpResponse<String> reported = send("POST",
                "/api/test/report-error?source=HOSTINGER&message=" + URLEncoder.encode("Rejected " + token, StandardCharsets.UTF_8));
        String listed = send("GET", "/api/log").body();

        assertThat(reported.statusCode()).isEqualTo(200);
        assertThat(listed).contains("Rejected ****");
        assertThat(output.getAll()).contains("Rejected ****");
        for (String text : new String[] {reported.body(), listed, output.getAll()}) {
            assertThat(text).doesNotContain(token);
        }
    }

    @Test
    void theStatusApiIsInTheApiDescription() throws Exception {
        String docs = send("GET", "/v3/api-docs").body();

        assertThat(docs).contains("\"/api/status\"").contains("getStatus").contains("\"SourceStatus\"")
                .contains("\"ConnectionState\"").contains("NOT_CONNECTED").contains("LIMITED");
    }

    private String states() throws Exception {
        JsonNode sources = JSON.readTree(send("GET", "/api/status").body()).get("sources");
        StringBuilder result = new StringBuilder();
        for (JsonNode source : sources) {
            if (!result.isEmpty()) {
                result.append(',');
            }
            result.append(source.get("source").asString()).append('=').append(source.get("state").asString());
        }
        return result.toString();
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
