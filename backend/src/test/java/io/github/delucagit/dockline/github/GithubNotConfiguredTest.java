package io.github.delucagit.dockline.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Without a Client ID (the default), connecting fails with a message the user can act on. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // Never the real GitHub, even if something went wrong: nothing listens on port 9.
        properties = {"dockline.github.api-url=http://127.0.0.1:9", "dockline.github.oauth-url=http://127.0.0.1:9"})
class GithubNotConfiguredTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Value("${local.server.port}")
    int port;

    @Test
    void connectingExplainsThatTheClientIdIsMissing() throws Exception {
        HttpResponse<String> response = send("POST", "/api/github/connect");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.get("title").asString()).isEqualTo("GitHub is not set up");
        assertThat(problem.get("detail").asString()).startsWith("GitHub client ID is not configured")
                .contains("DOCKLINE_GITHUB_CLIENT_ID");
    }

    @Test
    void theStatusSaysSoToo() throws Exception {
        JsonNode github = JSON.readTree(send("GET", "/api/status").body()).get("sources").get(0);

        assertThat(github.get("state").asString()).isEqualTo("NOT_CONNECTED");
        assertThat(github.get("message").asString()).startsWith("GitHub client ID is not configured");
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
