package io.github.delucagit.dockline.github;

import io.swagger.v3.oas.annotations.media.Schema;

/** The request and response shapes of the GitHub connection endpoints. */
final class GithubApi {

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
}
