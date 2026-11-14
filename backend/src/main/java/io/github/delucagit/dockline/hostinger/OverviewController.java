package io.github.delucagit.dockline.hostinger;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The Overview start page in one call. See {@link Overview} for where each part comes from. */
@RestController
@RequestMapping("/api/overview")
@Tag(name = "Overview", description = "Counts, problems, latest deploys and source status for the start page")
class OverviewController {

    private final Overview overview;

    OverviewController(Overview overview) {
        this.overview = overview;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getOverview", summary = "Everything the Overview page shows, in one call",
            description = "Always answers 200. Built from the same data as the other views, with their saved copies "
                    + "and the Hostinger cap of 50 requests per minute: the website list and the domains (copies up "
                    + "to 60 s old), the deploys (20 s), the sites' Git connections (five minutes, as in GET "
                    + "/api/sites/links) and the GitHub repositories (asked on every call, as in GET "
                    + "/api/github/repositories). Problems are failed latest deploys, expired domains and domains "
                    + "expiring within 30 days, broken site–repo links (a linked repository that is not among the "
                    + "user's GitHub repositories; not checked when the repository list is truncated) and sources "
                    + "that are not connected or not working fully. A source that cannot be asked does not fail the "
                    + "request: each part that needs it has an entry in its `errors` (codes HOSTINGER_NOT_CONNECTED, "
                    + "HOSTINGER_TOKEN_REJECTED, HOSTINGER_RATE_LIMITED, HOSTINGER_UNAVAILABLE, GITHUB_NOT_CONNECTED, "
                    + "GITHUB_TOKEN_REJECTED, GITHUB_RATE_LIMITED, GITHUB_UNAVAILABLE), and the parts of the other "
                    + "source are still filled. Failures are written to the Log section by the views' own code, "
                    + "and update `sources` at once.")
    @ApiResponse(responseCode = "200", description = "The overview")
    OverviewApi.Summary overview(
            @Parameter(description = "True skips the saved copies of the website list, the domains and the "
                    + "deploys and asks Hostinger again (counts towards the 50 requests per minute); the Git "
                    + "connections keep their five-minute copies")
            @RequestParam(defaultValue = "false") boolean refresh) {
        return overview.summary(refresh);
    }
}
