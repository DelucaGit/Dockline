package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.github.GithubApi.ConnectProgress;
import io.github.delucagit.dockline.github.GithubApi.DeviceCode;
import io.github.delucagit.dockline.github.GithubApi.Repositories;
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
import org.springframework.web.bind.annotation.GetMapping;
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
@Tag(name = "GitHub", description = "Connect to GitHub (device flow), check and disconnect; list repositories")
class GithubController {

    private final GithubDeviceFlow deviceFlow;
    private final GithubStatusProvider status;
    private final TokenStore tokenStore;
    private final GithubRepositoryList repositoryList;

    GithubController(GithubDeviceFlow deviceFlow, GithubStatusProvider status, TokenStore tokenStore,
            GithubRepositoryList repositoryList) {
        this.deviceFlow = deviceFlow;
        this.status = status;
        this.tokenStore = tokenStore;
        this.repositoryList = repositoryList;
    }

    @GetMapping(path = "/repositories", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listGithubRepositories", summary = "The user's repositories, fetched from GitHub now",
            description = "Every call asks GitHub (no cache) and follows all pages. Errors are problem responses with a "
                    + "stable `code` property: GITHUB_NOT_CONNECTED (409), GITHUB_TOKEN_REJECTED (409, reconnect), "
                    + "GITHUB_RATE_LIMITED (429, Retry-After header when known) and GITHUB_UNAVAILABLE (502). GitHub "
                    + "failures are written to the Log section and update GITHUB in GET /api/status at once.")
    @ApiResponse(responseCode = "200", description = "All repositories, most recently pushed first")
    @ApiResponse(responseCode = "409", description = "code GITHUB_NOT_CONNECTED: no GitHub token saved; "
            + "code GITHUB_TOKEN_REJECTED: GitHub rejected the saved token (revoked or expired), reconnect",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "code GITHUB_RATE_LIMITED: GitHub's rate limit is reached",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "code GITHUB_UNAVAILABLE: GitHub could not be reached, timed out, "
            + "failed or answered unexpectedly",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    Repositories repositories() {
        return repositoryList.list();
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
