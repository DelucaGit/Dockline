package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.hostinger.SiteLinkApi.SourceProblem;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** The response shapes of the deploy status endpoint ({@code GET /api/sites/deploys}). */
final class SiteDeployApi {

    private SiteDeployApi() {}

    @Schema(name = "SiteDeployStatus", enumAsRef = true, description = """
            How a site's latest deploy went. \
            SUCCEEDED: it finished and is live. \
            FAILED: it failed. \
            IN_PROGRESS: it is waiting or running. \
            UNKNOWN: there may be a deploy, but its status is not known: the source could not be asked (see the \
            site's problems) or reported a state Dockline does not know. \
            NONE: no deploy information exists for this site (it is not a Node.js website, or it was never built).""")
    enum DeployStatus {
        SUCCEEDED,
        FAILED,
        IN_PROGRESS,
        UNKNOWN,
        NONE
    }

    @Schema(name = "SiteDeploySource", enumAsRef = true, description = """
            Where the deploy information comes from. \
            HOSTINGER: Hostinger's build history of a Node.js website. \
            NONE: no source has deploy information for this site.""")
    enum DeployOrigin {
        HOSTINGER,
        NONE
    }

    @Schema(name = "SiteDeployCommit", description = "The Git commit a deploy was built from")
    record Commit(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    example = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0", description = "The full commit hash")
            String sha,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "Initial commit",
                    description = "The commit message; null when the source gives none") String message) {}

    @Schema(name = "SiteDeploy", description = "The latest deploy of one site")
    record SiteDeploy(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "example.com",
                    description = "The site's domain, lower case") String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DeployStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2024-05-29T05:51:02Z",
                    description = "When the deploy last changed state (ISO-8601, UTC): when it finished for "
                            + "SUCCEEDED and FAILED, its latest step for IN_PROGRESS; null when status is NONE, when "
                            + "the source could not be asked, or when the source gives no time") Instant at,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The commit that was deployed; null when not known (no deploy, a deploy that has "
                            + "not fetched the repository yet, or one from an uploaded archive)") Commit commit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "NONE exactly when status "
                    + "is NONE; HOSTINGER otherwise, also when Hostinger was asked and failed (status UNKNOWN)")
            DeployOrigin source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Why this site's deploy could not "
                    + "be read: at most one HOSTINGER entry (status is then UNKNOWN); empty when the source answered")
            List<SourceProblem> problems) {}

    @Schema(name = "SiteDeploys", description = "The latest deploy of every site, for the Sites list")
    record SiteDeploys(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One entry per Hostinger website "
                    + "that has a domain, sorted by domain") List<SiteDeploy> deploys,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-13T09:00:00Z",
                    description = "When Dockline fetched the deploy information from Hostinger (ISO-8601, UTC): the "
                            + "oldest answer used, up to 20 s old when saved copies were reused; the time of the "
                            + "request when Hostinger was not asked about any site") Instant fetchedAt) {}
}
