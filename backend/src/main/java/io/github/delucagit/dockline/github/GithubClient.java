package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.ExternalApiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
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
    /** The REST API version Dockline is written against (still supported by GitHub). */
    static final String API_VERSION = "2022-11-28";
    /** GitHub's maximum page size. */
    static final int PER_PAGE = 100;

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
        return getApi(URI.create(userUrl()), token);
    }

    /**
     * The first page of the user's repositories: all they own, collaborate on or can see through an
     * organization, private ones included (GitHub's defaults, spelled out), 100 per page, most recently pushed first.
     */
    String repositoriesUrl() {
        return github.apiUrl() + "/user/repos?visibility=all&affiliation=owner,collaborator,organization_member"
                + "&sort=pushed&direction=desc&per_page=" + PER_PAGE;
    }

    /** The first page of the user's repositories, see {@link #repositoriesUrl()}. */
    Reply getRepositories(String token) {
        return getApi(URI.create(repositoriesUrl()), token);
    }

    /** One repository, {@code GET /repos/{owner}/{repo}}. {@code fullName} must be a checked {@code owner/name}. */
    String repositoryUrl(String fullName) {
        return github.apiUrl() + "/repos/" + fullName;
    }

    /**
     * One repository by full name: 200 when the token can see it, 404 when it does not exist or is hidden from
     * the token, 403 when access is refused, 301 when it was renamed or moved (redirects are not followed).
     */
    Reply getRepository(String fullName, String token) {
        return getApi(URI.create(repositoryUrl(fullName)), token);
    }

    /**
     * A further page, from the {@code next} link of the page before. Refuses (with {@link IllegalArgumentException})
     * any address outside the configured GitHub API, so the token is never sent to another host.
     */
    Reply getPage(URI next, String token) {
        if (!isApiAddress(next)) {
            throw new IllegalArgumentException("Not a GitHub API address: " + next.getScheme() + "://" + next.getAuthority());
        }
        return getApi(next, token);
    }

    /** True when the address has the same scheme, host and port as the configured GitHub API. */
    boolean isApiAddress(URI address) {
        URI api = URI.create(github.apiUrl());
        return address.getScheme() != null && address.getHost() != null
                && address.getScheme().equalsIgnoreCase(api.getScheme())
                && address.getHost().equalsIgnoreCase(api.getHost())
                && effectivePort(address) == effectivePort(api);
    }

    /**
     * The {@code rel="next"} address of GitHub's {@code Link} header, e.g.
     * {@code <https://api.github.com/user/repos?page=2>; rel="next", <...>; rel="last"}; empty on the last page.
     */
    static Optional<URI> nextLink(HttpHeaders headers) {
        for (String header : headers.getOrEmpty(HttpHeaders.LINK)) {
            for (String part : header.split(",(?=\\s*<)")) {
                int open = part.indexOf('<');
                int close = part.indexOf('>', open + 1);
                if (open < 0 || close < 0) {
                    continue;
                }
                if (hasRelNext(part.substring(close + 1))) {
                    try {
                        return Optional.of(URI.create(part.substring(open + 1, close).strip()));
                    } catch (IllegalArgumentException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** {@code ; rel="next"} (rel may hold several space-separated values). */
    private static boolean hasRelNext(String params) {
        for (String param : params.split(";")) {
            String[] nameValue = param.split("=", 2);
            if (nameValue.length == 2 && nameValue[0].strip().equalsIgnoreCase("rel")) {
                String value = nameValue[1].strip().replace("\"", "");
                for (String rel : value.split("\\s+")) {
                    if (rel.equalsIgnoreCase("next")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    /** A GET on the REST API with the token, as GitHub's documentation asks for. The address is used as-is. */
    private Reply getApi(URI address, String token) {
        return http.get().uri(address)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .header("X-GitHub-Api-Version", API_VERSION)
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
