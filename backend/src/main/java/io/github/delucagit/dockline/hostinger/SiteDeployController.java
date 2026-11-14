package io.github.delucagit.dockline.hostinger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The latest deploy of every site. See {@link SiteDeploys} and {@link HostingerBuilds} for the rules. */
@RestController
@RequestMapping("/api/sites")
@Tag(name = "Sites")
class SiteDeployController {

    private final SiteDeploys siteDeploys;

    SiteDeployController(SiteDeploys siteDeploys) {
        this.siteDeploys = siteDeploys;
    }

    @GetMapping(path = "/deploys", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSiteDeploys", summary = "The latest deploy of every site, for the Sites list",
            description = "For each Hostinger website with a domain: the status, time and commit of its latest "
                    + "deploy. Deploy information exists only for Node.js websites (`website_type` nodejs), from "
                    + "Hostinger's build history (source HOSTINGER); every other website, and a Node.js website "
                    + "that was never built, has status NONE and source NONE, and costs no Hostinger request. A "
                    + "Node.js website costs one Hostinger request (two with more than 100 builds). The answers "
                    + "are kept for 20 seconds, so calling this every 30 seconds shows changes; `refresh=true` "
                    + "asks Hostinger again at once. The website list itself is the copy saved in the last 60 s. "
                    + "A failure for one site does not fail the request: that site has status UNKNOWN and one "
                    + "entry in its `problems` (codes HOSTINGER_UNAVAILABLE, HOSTINGER_RATE_LIMITED with "
                    + "`retryAfterSeconds`, HOSTINGER_TOKEN_REJECTED, HOSTINGER_NOT_CONNECTED), and the failure is "
                    + "written to the Log section. After a rate limit or a rejected token Hostinger is not asked "
                    + "about the remaining sites in that call: they are answered from the saved answers, or get "
                    + "the same problem. Only when the website list itself cannot be read is the answer a problem "
                    + "response, with the same codes as GET /api/hostinger/sites.")
    @ApiResponse(responseCode = "200", description = "The latest deploy per site, sorted by domain")
    @ApiResponse(responseCode = "409", description = "code HOSTINGER_NOT_CONNECTED: no Hostinger token saved; "
            + "code HOSTINGER_TOKEN_REJECTED: Hostinger rejected the saved token (revoked or expired), replace it",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "code HOSTINGER_RATE_LIMITED: Hostinger's rate limit is reached",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "code HOSTINGER_UNAVAILABLE: Hostinger could not be reached, "
            + "timed out, failed or answered unexpectedly",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    SiteDeployApi.SiteDeploys deploys(
            @Parameter(description = "True skips the deploy answers saved in the last 20 seconds and asks "
                    + "Hostinger again about every Node.js website (counts towards the 50 requests per minute)")
            @RequestParam(defaultValue = "false") boolean refresh) {
        return siteDeploys.list(refresh);
    }
}
