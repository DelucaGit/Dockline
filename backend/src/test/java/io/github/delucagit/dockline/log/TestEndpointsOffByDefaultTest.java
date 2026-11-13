package io.github.delucagit.dockline.log;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** With the default configuration the test-only endpoints do not exist. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestEndpointsOffByDefaultTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void testEndpointsAreAbsent() throws Exception {
        for (String path : new String[] {"/api/test/report-error", "/api/test/fail"}) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();

            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(404);
        }
    }
}
