package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ExternalApiProperties;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The raw HTTP calls to the Hostinger public API (bearer token, see developers.hostinger.com).
 * Every Hostinger call goes through this class, so a shared rate limit and cache can be added in one place.
 * Every answer comes back as a {@link Reply}, whatever its status; only network failures (no answer at all,
 * including timeouts) throw {@link RestClientException}.
 */
@Component
class HostingerClient {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    /**
     * Used to check a token: a cheap read-only list that every account has (an account may have no domains,
     * websites or VPS, but always has a billing context). Answers 200 with a JSON array, 401 for a bad token.
     */
    static final String SUBSCRIPTIONS_PATH = "/api/billing/v1/subscriptions";

    private final ExternalApiProperties.Hostinger hostinger;
    private final RestClient http;

    HostingerClient(ExternalApiProperties apis) {
        this.hostinger = apis.hostinger();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(READ_TIMEOUT);
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** An HTTP answer from Hostinger. */
    record Reply(int status, HttpHeaders headers, String body) {
        boolean ok() {
            return status >= 200 && status < 300;
        }

        /** 401 (what Hostinger documents for a missing, expired or revoked token) or 403. */
        boolean tokenRejected() {
            return status == 401 || status == 403;
        }
    }

    String subscriptionsUrl() {
        return hostinger.apiUrl() + SUBSCRIPTIONS_PATH;
    }

    /** Lists the account's subscriptions; used to check that a token works. */
    Reply getSubscriptions(String token) {
        return http.get().uri(subscriptionsUrl())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .accept(MediaType.APPLICATION_JSON)
                .exchange((request, response) -> new Reply(response.getStatusCode().value(), response.getHeaders(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
    }
}
