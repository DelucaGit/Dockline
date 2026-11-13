package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.ExternalApiProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The raw HTTP calls to GitHub. Every answer comes back as a {@link Reply}, whatever its status;
 * only network failures (no answer at all) throw {@link RestClientException}.
 */
@Component
class GithubClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /** What Dockline asks for, see docs/github-oauth-app-setup.md. */
    static final String SCOPES = "read:user repo";
    static final String DEVICE_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:device_code";

    private final ExternalApiProperties.Github github;
    private final RestClient http;

    GithubClient(ExternalApiProperties apis) {
        this.github = apis.github();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** An HTTP answer from GitHub. */
    record Reply(int status, HttpHeaders headers, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    String deviceCodeUrl() {
        return github.oauthUrl() + "/login/device/code";
    }

    String tokenUrl() {
        return github.oauthUrl() + "/login/oauth/access_token";
    }

    String userUrl() {
        return github.apiUrl() + "/user";
    }

    /** Step 1 of the device flow: ask for a device code and a user code. */
    Reply requestDeviceCode() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", github.clientId());
        form.add("scope", SCOPES);
        return postForm(deviceCodeUrl(), form);
    }

    /** Step 3 of the device flow: has the user approved yet? */
    Reply requestToken(String deviceCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", github.clientId());
        form.add("device_code", deviceCode);
        form.add("grant_type", DEVICE_GRANT_TYPE);
        return postForm(tokenUrl(), form);
    }

    /** Who owns this token? Used to check that a stored token still works. */
    Reply getUser(String token) {
        return http.get().uri(userUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.getHeaders(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
    }

    private Reply postForm(String url, MultiValueMap<String, String> form) {
        return http.post().uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.getHeaders(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
    }
}
