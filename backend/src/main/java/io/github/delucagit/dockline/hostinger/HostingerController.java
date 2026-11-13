package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.log.SecretMasker;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;

/**
 * Saving, checking and removing the Hostinger API token. A token is checked with Hostinger before it is
 * stored, so a bad token is never saved (and never replaces a working one). The token never appears in any
 * response or log line.
 */
@RestController
@RequestMapping("/api/hostinger")
@Tag(name = "Hostinger", description = "Save (after checking), check and remove the Hostinger API token; list websites")
class HostingerController {

    /** Hostinger tokens are far shorter; anything longer is not a token. */
    static final int MAX_TOKEN_LENGTH = 1024;

    private final HostingerClient client;
    private final HostingerStatusProvider status;
    private final TokenStore tokenStore;
    private final HostingerSiteList siteList;

    HostingerController(HostingerClient client, HostingerStatusProvider status, TokenStore tokenStore,
            HostingerSiteList siteList) {
        this.client = client;
        this.status = status;
        this.tokenStore = tokenStore;
        this.siteList = siteList;
    }

    @GetMapping(path = "/sites", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listHostingerSites", summary = "The user's Hostinger websites, fetched from Hostinger now",
            description = "Follows all pages of Hostinger's website list. Errors are problem responses with a stable "
                    + "`code` property: HOSTINGER_NOT_CONNECTED (409), HOSTINGER_TOKEN_REJECTED (409, replace the "
                    + "token), HOSTINGER_RATE_LIMITED (429, Retry-After header when Hostinger sends one) and "
                    + "HOSTINGER_UNAVAILABLE (502). Hostinger failures are written to the Log section and update "
                    + "HOSTINGER in GET /api/status at once.")
    @ApiResponse(responseCode = "200", description = "All websites, sorted by domain")
    @ApiResponse(responseCode = "409", description = "code HOSTINGER_NOT_CONNECTED: no Hostinger token saved; "
            + "code HOSTINGER_TOKEN_REJECTED: Hostinger rejected the saved token (revoked or expired), replace it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "code HOSTINGER_RATE_LIMITED: Hostinger's rate limit is reached",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "code HOSTINGER_UNAVAILABLE: Hostinger could not be reached, "
            + "timed out, failed or answered unexpectedly",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    HostingerApi.Sites sites() {
        return siteList.list();
    }

    /** The token the user pasted. */
    @Schema(name = "HostingerTokenRequest", description = "A Hostinger API token created in hPanel")
    record TokenRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = MAX_TOKEN_LENGTH,
                    description = "The API token; surrounding whitespace is ignored") String token) {}

    @PutMapping(path = "/token", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "saveHostingerToken", summary = "Check a Hostinger API token with Hostinger, then save it",
            description = "Replaces any saved token, but only if Hostinger accepts the new one. On any error nothing "
                    + "is saved and an earlier token stays as it was.")
    @ApiResponse(responseCode = "200", description = "Hostinger accepted the token; it is saved (state CONNECTED)")
    @ApiResponse(responseCode = "400", description = "The token is missing, blank or too long",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Hostinger rejected the token (HTTP 401/403); not saved",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "Hostinger's rate limit is reached; not saved, try again later",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "Hostinger could not be reached or answered with an error; not saved",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    SourceStatus save(@RequestBody TokenRequest request) {
        String token = request == null || request.token() == null ? "" : request.token().strip();
        if (token.isEmpty()) {
            throw new HostingerTokenException(HttpStatus.BAD_REQUEST, "Paste a Hostinger API token first.");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new HostingerTokenException(HttpStatus.BAD_REQUEST,
                    "That is too long to be a Hostinger API token (more than " + MAX_TOKEN_LENGTH + " characters).");
        }
        // Before any call, so even an upstream error body that echoes the token is masked.
        SecretMasker.register(token);
        HostingerClient.Reply reply;
        try {
            reply = client.getSubscriptions(token);
        } catch (RestClientException e) {
            throw new HostingerTokenException(HttpStatus.BAD_GATEWAY,
                    "Could not reach Hostinger, so the token was not saved. Check your internet connection and try again.");
        }
        if (reply.tokenRejected()) {
            throw new HostingerTokenException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "Hostinger rejected this token (HTTP " + reply.status() + "), so it was not saved. Check that you "
                            + "copied the whole token from hPanel and that it has not expired or been deleted.");
        }
        if (reply.status() == 429) {
            HostingerTokenException limited = new HostingerTokenException(HttpStatus.TOO_MANY_REQUESTS,
                    "Hostinger's rate limit is reached, so the token could not be checked and was not saved. "
                            + "Try again in a minute.");
            limited.retryAfter = reply.headers().getFirst(HttpHeaders.RETRY_AFTER);
            throw limited;
        }
        if (!reply.ok()) {
            throw new HostingerTokenException(HttpStatus.BAD_GATEWAY, "Could not reach Hostinger (it answered HTTP "
                    + reply.status() + "), so the token was not saved. Try again later.");
        }
        tokenStore.save(Source.HOSTINGER, token);
        return status.connected(token);
    }

    @DeleteMapping("/token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "deleteHostingerToken", summary = "Remove the Hostinger token from Dockline",
            description = "Afterwards HOSTINGER is NOT_CONNECTED. Does nothing when no token is saved.")
    @ApiResponse(responseCode = "204", description = "Removed")
    void delete() {
        tokenStore.delete(Source.HOSTINGER);
        status.invalidate();
    }

    @PostMapping(path = "/recheck", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "recheckHostinger", summary = "Check the saved token with Hostinger now, skipping the cache",
            description = "GET /api/status re-checks the token at most once a minute; this forces a check.")
    SourceStatus recheck() {
        return status.recheck();
    }

    @ExceptionHandler(HostingerTokenException.class)
    ResponseEntity<ProblemDetail> tokenNotSaved(HostingerTokenException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(e.status, e.getMessage());
        problem.setTitle(e.status == HttpStatus.BAD_REQUEST ? "No valid token given" : "Hostinger token not saved");
        ResponseEntity.BodyBuilder response = ResponseEntity.status(e.status);
        if (e.retryAfter != null) {
            response.header(HttpHeaders.RETRY_AFTER, e.retryAfter);
        }
        return response.body(problem);
    }

    /** Saving the token failed; answered as a problem response with a message for the user. */
    static final class HostingerTokenException extends RuntimeException {
        final HttpStatus status;
        String retryAfter;

        HostingerTokenException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
