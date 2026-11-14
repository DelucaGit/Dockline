package io.github.delucagit.dockline.github;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** The request and response shapes of the GitHub endpoints. */
public final class GithubApi {

    private GithubApi() {}

    /** What the user needs to approve Dockline on GitHub. The device code itself stays in the backend. */
    @Schema(name = "GithubDeviceCode", description = "Show userCode and open verificationUri; then poll every intervalSeconds")
    record DeviceCode(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "WDJB-MJHT",
                    description = "Short code the user types on GitHub's page") String userCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "https://github.com/login/device",
                    description = "Page to open in the browser") String verificationUri,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "900",
                    description = "Seconds until the code expires") long expiresInSeconds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "5",
                    description = "Poll POST /api/github/connect/poll no more often than this") long intervalSeconds) {}

    /** Where the connection attempt stands. */
    @Schema(name = "GithubConnectState", enumAsRef = true, description = """
            NOT_STARTED: no connection attempt since the app started (or since disconnect). \
            PENDING: waiting for the user to approve on GitHub; poll again after intervalSeconds. \
            CONNECTED: approved; the token is saved. \
            DENIED: the user clicked Cancel on GitHub; start again to retry. \
            EXPIRED: the code expired before it was approved; start again to retry. \
            ERROR: something else went wrong, see message; start again to retry.""")
    enum ConnectState {
        NOT_STARTED,
        PENDING,
        CONNECTED,
        DENIED,
        EXPIRED,
        ERROR
    }

    @Schema(name = "GithubConnectProgress", description = "Result of checking whether the user has approved yet")
    record ConnectProgress(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ConnectState state,
            @Schema(nullable = true, description = "For PENDING: seconds to wait before polling again "
                    + "(grows by 5 when GitHub asks Dockline to slow down)") Long intervalSeconds,
            @Schema(nullable = true, description = "For DENIED, EXPIRED and ERROR: what happened, for the user") String message) {

        static ConnectProgress of(ConnectState state, String message) {
            return new ConnectProgress(state, null, message);
        }
    }

    @Schema(name = "GithubVisibility", enumAsRef = true, description = "PRIVATE: only people with access see it. PUBLIC: anyone can.")
    public enum Visibility {
        PRIVATE,
        PUBLIC
    }

    @Schema(name = "GithubRepository", description = "One repository the user owns, collaborates on or sees through an organization")
    public record Repository(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "dockline") String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "octocat/dockline",
                    description = "owner/name") String fullName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Visibility visibility,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-11-12T08:15:30Z",
                    description = "Last push (ISO-8601, UTC); null for a repository nobody has pushed to") Instant lastPushAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "https://github.com/octocat/dockline",
                    description = "The repository's page on GitHub") String htmlUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "A fork of another repository") boolean fork,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Archived (read-only) on GitHub") boolean archived) {}

    @Schema(name = "GithubRepositories", description = "The user's repositories, fetched from GitHub just now")
    public record Repositories(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Most recently pushed first; never-pushed ones last") List<Repository> repositories,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-13T09:00:00Z",
                    description = "When Dockline fetched this from GitHub (ISO-8601, UTC)") Instant fetchedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True when the list stopped at "
                    + GithubRepositoryList.MAX_PAGES + " pages of 100 and more repositories exist") boolean truncated) {}
}
