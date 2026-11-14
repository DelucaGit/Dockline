package io.github.delucagit.dockline;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Base URLs of the external APIs the backend talks to. The real addresses are the defaults
 * (see application.properties); tests point them at a fake server (WireMock) instead, e.g. with
 * the environment variable DOCKLINE_GITHUB_API_URL=http://127.0.0.1:12345.
 */
@ConfigurationProperties("dockline")
public record ExternalApiProperties(Github github, Hostinger hostinger) {

    /**
     * GitHub REST API (api.github.com), OAuth device flow (github.com/login) and the Client ID of the
     * Dockline OAuth App (empty when not configured; see docs/github-oauth-app-setup.md).
     */
    public record Github(String apiUrl, String oauthUrl, String clientId) {

        /** True when a Client ID is set, so "Connect to GitHub" can work. */
        public boolean hasClientId() {
            return clientId != null && !clientId.isBlank();
        }
    }

    /**
     * Hostinger public API (developers.hostinger.com) and how long to wait for its answer once connected
     * (default 30 s; Hostinger's builds endpoint can be slow while a site is being deployed).
     */
    public record Hostinger(String apiUrl, @DefaultValue("30s") Duration readTimeout) {}
}
