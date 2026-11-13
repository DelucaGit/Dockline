package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.ExternalApiProperties;
import io.github.delucagit.dockline.github.GithubApi.ConnectProgress;
import io.github.delucagit.dockline.github.GithubApi.ConnectState;
import io.github.delucagit.dockline.github.GithubApi.DeviceCode;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.log.SecretMasker;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import io.github.delucagit.dockline.token.TokenStoreUnavailableException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * GitHub's OAuth device flow (https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps#device-flow).
 * One attempt at a time: starting again replaces the running one. The device code never leaves the backend.
 *
 * <p>The frontend may poll as often as it likes; GitHub is only asked once the interval GitHub gave has passed
 * (it grows by 5 s on {@code slow_down}). Until then the answer is PENDING without calling GitHub.
 */
@Service
class GithubDeviceFlow {

    static final String NOT_CONFIGURED = "GitHub client ID is not configured. Set DOCKLINE_GITHUB_CLIENT_ID "
            + "(see docs/github-oauth-app-setup.md) and restart Dockline.";
    static final long SLOW_DOWN_SECONDS = 5;

    private final ExternalApiProperties.Github github;
    private final GithubClient client;
    private final TokenStore tokenStore;
    private final GithubStatusProvider status;
    private final ProblemLog problemLog;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    /** The running attempt, or null. Guarded by this. */
    private Flow flow;
    /** How the last attempt ended, shown until the next start. Guarded by this. */
    private ConnectProgress finished = ConnectProgress.of(ConnectState.NOT_STARTED, null);

    private record Flow(String deviceCode, Instant expiresAt, long intervalSeconds, Instant nextPollAt) {}

    GithubDeviceFlow(ExternalApiProperties apis, GithubClient client, TokenStore tokenStore,
            GithubStatusProvider status, ProblemLog problemLog, Clock clock) {
        this.github = apis.github();
        this.client = client;
        this.tokenStore = tokenStore;
        this.status = status;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    /** Asks GitHub for a new code. Throws {@link GithubConnectException} when that is not possible. */
    synchronized DeviceCode start() {
        if (!github.hasClientId()) {
            throw new GithubConnectException(HttpStatus.BAD_REQUEST, NOT_CONFIGURED);
        }
        flow = null;
        String action = "POST /login/device/code";
        String request = "POST " + client.deviceCodeUrl() + " (scope: " + GithubClient.SCOPES + ")";
        GithubClient.Reply reply;
        try {
            reply = client.requestDeviceCode();
        } catch (RestClientException e) {
            String message = "Could not reach GitHub to start connecting: " + e.getMessage();
            problemLog.report(LogLevel.ERROR, LogSource.GITHUB, action, null, message, request, null, e);
            throw new GithubConnectException(HttpStatus.BAD_GATEWAY, message);
        }
        JsonNode body = parse(reply.body());
        if (!reply.ok() || body == null || body.hasNonNull("error") || !body.hasNonNull("device_code")) {
            String message = "GitHub did not start connecting: " + describe(reply, body);
            problemLog.report(LogLevel.ERROR, LogSource.GITHUB, action, reply.status(), message, request,
                    reply.body(), null);
            throw new GithubConnectException(HttpStatus.BAD_GATEWAY, message);
        }
        String deviceCode = body.get("device_code").asString();
        SecretMasker.register(deviceCode);
        long expiresIn = body.path("expires_in").asLong(900);
        long interval = Math.max(1, body.path("interval").asLong(5));
        Instant now = clock.instant();
        flow = new Flow(deviceCode, now.plusSeconds(expiresIn), interval, now.plusSeconds(interval));
        finished = null;
        return new DeviceCode(body.path("user_code").asString(), body.path("verification_uri").asString(),
                expiresIn, interval);
    }

    /** Has the user approved yet? Calls GitHub only when the polling interval allows it. */
    synchronized ConnectProgress poll() {
        if (flow == null) {
            return finished;
        }
        Instant now = clock.instant();
        if (!now.isBefore(flow.expiresAt())) {
            return finish(ConnectProgress.of(ConnectState.EXPIRED, "The code expired before it was approved. Try again."));
        }
        if (now.isBefore(flow.nextPollAt())) {
            return pending();
        }
        String action = "POST /login/oauth/access_token";
        String request = "POST " + client.tokenUrl() + " (device flow)";
        GithubClient.Reply reply;
        try {
            reply = client.requestToken(flow.deviceCode());
        } catch (RestClientException e) {
            String message = "Could not reach GitHub to finish connecting: " + e.getMessage();
            problemLog.report(LogLevel.ERROR, LogSource.GITHUB, action, null, message, request, null, e);
            return finish(ConnectProgress.of(ConnectState.ERROR, message));
        }
        JsonNode body = parse(reply.body());
        if (body != null && body.hasNonNull("access_token")) {
            return saveToken(body.get("access_token").asString());
        }
        String error = body == null ? "" : body.path("error").asString("");
        switch (error) {
            case "authorization_pending" -> {
                flow = new Flow(flow.deviceCode(), flow.expiresAt(), flow.intervalSeconds(),
                        now.plusSeconds(flow.intervalSeconds()));
                return pending();
            }
            case "slow_down" -> {
                long interval = Math.max(flow.intervalSeconds() + SLOW_DOWN_SECONDS, body.path("interval").asLong(0));
                flow = new Flow(flow.deviceCode(), flow.expiresAt(), interval, now.plusSeconds(interval));
                return pending();
            }
            case "access_denied" -> {
                return finish(ConnectProgress.of(ConnectState.DENIED, "Access was denied on GitHub. Try again."));
            }
            case "expired_token" -> {
                return finish(ConnectProgress.of(ConnectState.EXPIRED, "The code expired before it was approved. Try again."));
            }
            default -> {
                String message = "GitHub could not finish connecting: " + describe(reply, body);
                problemLog.report(LogLevel.ERROR, LogSource.GITHUB, action, reply.status(), message, request,
                        reply.body(), null);
                return finish(ConnectProgress.of(ConnectState.ERROR, message));
            }
        }
    }

    /** Forgets any running attempt (used by disconnect). */
    synchronized void cancel() {
        flow = null;
        finished = ConnectProgress.of(ConnectState.NOT_STARTED, null);
    }

    private ConnectProgress saveToken(String token) {
        SecretMasker.register(token);
        try {
            tokenStore.save(Source.GITHUB, token);
        } catch (TokenStoreUnavailableException e) {
            problemLog.report(LogLevel.ERROR, LogSource.BACKEND, "Save GitHub token", null, e.getMessage(), null,
                    null, e);
            return finish(ConnectProgress.of(ConnectState.ERROR, e.getMessage()));
        }
        status.invalidate();
        return finish(ConnectProgress.of(ConnectState.CONNECTED, null));
    }

    private ConnectProgress pending() {
        return new ConnectProgress(ConnectState.PENDING, flow.intervalSeconds(), null);
    }

    private ConnectProgress finish(ConnectProgress result) {
        flow = null;
        finished = result;
        return result;
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? null : json.readTree(body);
        } catch (JacksonException e) {
            return null;
        }
    }

    private static String describe(GithubClient.Reply reply, JsonNode body) {
        if (body != null && body.hasNonNull("error")) {
            String description = body.path("error_description").asString("");
            return body.get("error").asString() + (description.isEmpty() ? "" : " (" + description + ")");
        }
        return "HTTP " + reply.status();
    }
}
