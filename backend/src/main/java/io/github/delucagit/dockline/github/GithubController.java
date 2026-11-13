package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.github.GithubApi.ConnectProgress;
import io.github.delucagit.dockline.github.GithubApi.DeviceCode;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Connecting to GitHub with the device flow, and disconnecting. The token never appears in any response.
 *
 * <ol>
 *   <li>{@code POST /api/github/connect}: show the user code, open the verification page
 *   <li>{@code POST /api/github/connect/poll} every few seconds until the state is no longer PENDING
 * </ol>
 */
@RestController
@RequestMapping("/api/github")
@Tag(name = "GitHub", description = "Connect to GitHub (device flow), check and disconnect")
class GithubController {

    private final GithubDeviceFlow deviceFlow;
    private final GithubStatusProvider status;
    private final TokenStore tokenStore;

    GithubController(GithubDeviceFlow deviceFlow, GithubStatusProvider status, TokenStore tokenStore) {
        this.deviceFlow = deviceFlow;
        this.status = status;
        this.tokenStore = tokenStore;
    }

    @PostMapping(path = "/connect", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "startGithubConnect", summary = "Start connecting: get a code for the user to enter on GitHub",
            description = "Replaces any connection attempt already running.")
    @ApiResponse(responseCode = "200", description = "Code to show")
    @ApiResponse(responseCode = "400", description = "GitHub client ID is not configured",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "GitHub could not be reached or refused",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    DeviceCode connect() {
        return deviceFlow.start();
    }

    @PostMapping(path = "/connect/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "pollGithubConnect", summary = "Has the user approved yet?",
            description = "Safe to call every few seconds: GitHub is only asked when its polling interval allows. "
                    + "On CONNECTED the token is saved and GET /api/status shows GITHUB as CONNECTED.")
    ConnectProgress poll() {
        return deviceFlow.poll();
    }

    @PostMapping("/disconnect")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "disconnectGithub", summary = "Remove the GitHub token from Dockline",
            description = "Also stops a running connection attempt. Afterwards GITHUB is NOT_CONNECTED.")
    @ApiResponse(responseCode = "204", description = "Disconnected")
    void disconnect() {
        deviceFlow.cancel();
        tokenStore.delete(Source.GITHUB);
        status.invalidate();
    }

    @PostMapping(path = "/recheck", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "recheckGithub", summary = "Check the saved token with GitHub now, skipping the cache",
            description = "GET /api/status re-checks the token at most once a minute; this forces a check.")
    SourceStatus recheck() {
        return status.recheck();
    }

    @ExceptionHandler(GithubConnectException.class)
    ProblemDetail connectFailed(GithubConnectException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status(), e.getMessage());
        problem.setTitle(e.status() == HttpStatus.BAD_REQUEST ? "GitHub is not set up" : "GitHub connection failed");
        return problem;
    }
}
