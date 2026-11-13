package io.github.delucagit.dockline;

import org.springframework.boot.context.properties.ConfigurationProperties;

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

    /** Hostinger public API (developers.hostinger.com). */
    public record Hostinger(String apiUrl) {}
}
