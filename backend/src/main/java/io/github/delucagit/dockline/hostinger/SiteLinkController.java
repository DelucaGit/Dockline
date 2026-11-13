package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.hostinger.SiteLinkApi.RepoRequest;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SiteDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which GitHub repository belongs to which site: the site detail, the links for the Sites list, and setting
 * and removing a manual link. See {@link SiteLinks} for the rules.
 */
@RestController
@RequestMapping("/api/sites")
@Tag(name = "Sites", description = "A site and its linked GitHub repository: found in Hostinger's Git settings, "
        + "or linked by hand")
class SiteLinkController {

    private final SiteLinks siteLinks;

    SiteLinkController(SiteLinks siteLinks) {
        this.siteLinks = siteLinks;
    }

    @GetMapping(path = "/links", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listSiteLinks", summary = "The linked repository of every site, for the Sites list",
            description = "Always answers 200. For each Hostinger website with a domain: the repository from "
                    + "Hostinger's Git settings (source HOSTINGER), else the manual link (MANUAL), else NONE. GitHub "
                    + "is not asked. Asking Hostinger costs one or two requests per site, so the answers are kept "
                    + "for five minutes, and one call asks about at most 40 requests' worth of sites: with more "
                    + "sites than that, the rest have `hostingerChecked` false (`complete` false) and are filled "
                    + "in by a later call. When Hostinger cannot be asked (not connected, rejected token, rate "
                    + "limit, outage) `problems` holds one HOSTINGER entry with the usual HOSTINGER_* code, and "
                    + "the saved manual links are still returned.")
    @ApiResponse(responseCode = "200", description = "The links, sorted by domain")
    SiteLinkApi.SiteLinks links() {
        return siteLinks.list();
    }

    @GetMapping(path = "/{domain}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getSite", summary = "One site and its linked GitHub repository",
            description = "The repository comes from Hostinger's Git settings of the site (source HOSTINGER; this "
                    + "always wins, and a saved manual link is then not shown), else from the manual link (MANUAL), "
                    + "else there is none (NONE). The repository is checked with GitHub: one that is missing or "
                    + "not accessible gives a `repoWarning`, not an error. A source that cannot be asked does not "
                    + "fail the request: it adds an entry to `problems` (codes HOSTINGER_NOT_CONNECTED, "
                    + "HOSTINGER_TOKEN_REJECTED, HOSTINGER_RATE_LIMITED, HOSTINGER_UNAVAILABLE, GITHUB_NOT_CONNECTED, "
                    + "GITHUB_TOKEN_REJECTED, GITHUB_RATE_LIMITED, GITHUB_UNAVAILABLE). Without Hostinger "
                    + "(`hostingerChecked` false) the answer comes from the saved manual links alone; without "
                    + "GitHub the repository is shown unchecked. Hostinger's answer about the site is kept for "
                    + "five minutes.")
    @ApiResponse(responseCode = "200", description = "The site")
    @ApiResponse(responseCode = "404", description = "code SITE_NOT_FOUND: Hostinger has no website with this domain",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    SiteDetail site(
            @Parameter(description = "The site's domain, e.g. example.com (case does not matter)")
            @PathVariable String domain,
            @Parameter(description = "True asks Hostinger again about this site's Git connection instead of using "
                    + "the answer saved in the last five minutes (one or two Hostinger requests)")
            @RequestParam(defaultValue = "false") boolean refresh) {
        return siteLinks.detail(domain, refresh);
    }

    @PutMapping(path = "/{domain}/repo", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "linkSiteRepo", summary = "Link a site to a GitHub repository by hand (or change the link)",
            description = "Saves the link in the settings file, replacing an earlier manual link of the site, and "
                    + "answers like GET /api/sites/{domain}. Refused when the site is Git-connected in Hostinger "
                    + "(SITE_GIT_CONNECTED): that connection always wins. Hostinger is asked afresh for this, so "
                    + "when Hostinger cannot be asked nothing is saved and the usual HOSTINGER_* problem is "
                    + "answered. GitHub is not needed: a repository that does not exist is saved and comes back "
                    + "with a `repoWarning`.")
    @ApiResponse(responseCode = "200", description = "Saved; the site with its new link (source MANUAL)")
    @ApiResponse(responseCode = "400", description = "code INVALID_REPOSITORY: repoFullName is missing or not owner/name",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "code SITE_NOT_FOUND: Hostinger has no website with this domain",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "code SITE_GIT_CONNECTED: the site is Git-connected in "
            + "Hostinger, a manual link cannot be set; code HOSTINGER_NOT_CONNECTED: no Hostinger token saved; "
            + "code HOSTINGER_TOKEN_REJECTED: Hostinger rejected the saved token",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "code HOSTINGER_RATE_LIMITED: Hostinger's rate limit is reached",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "500", description = "code SETTINGS_UNAVAILABLE: the settings file could not be written",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "502", description = "code HOSTINGER_UNAVAILABLE: Hostinger could not be reached, "
            + "timed out, failed or answered unexpectedly",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    SiteDetail link(
            @Parameter(description = "The site's domain, e.g. example.com (case does not matter)")
            @PathVariable String domain,
            @RequestBody RepoRequest request) {
        return siteLinks.link(domain, request == null ? null : request.repoFullName());
    }

    @DeleteMapping("/{domain}/repo")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "unlinkSiteRepo", summary = "Remove a site's manual link",
            description = "Does nothing when the site has no manual link. Needs neither Hostinger nor GitHub. A "
                    + "Hostinger Git connection is not touched (change that in hPanel).")
    @ApiResponse(responseCode = "204", description = "Removed (or there was none)")
    @ApiResponse(responseCode = "500", description = "code SETTINGS_UNAVAILABLE: the settings file could not be written",
            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
    void unlink(
            @Parameter(description = "The site's domain, e.g. example.com (case does not matter)")
            @PathVariable String domain) {
        siteLinks.unlink(domain);
    }
}
