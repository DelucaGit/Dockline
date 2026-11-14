package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.hostinger.SiteDeployApi.Commit;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployStatus;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SourceProblem;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/** The response shape of the overview endpoint ({@code GET /api/overview}). */
final class OverviewApi {

    private OverviewApi() {}

    @Schema(name = "OverviewCount", description = "A number on the Overview page, or why it could not be counted")
    record Count(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "3",
                    description = "The number; null when its source could not be asked (see errors)") Integer count,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Why the number is missing: at most "
                    + "one entry, from the source it comes from; empty when the source answered")
            List<SourceProblem> errors) {}

    @Schema(name = "OverviewProblemKind", enumAsRef = true, description = """
            What needs attention. \
            DEPLOY_FAILED: a site's latest deploy failed. \
            DOMAIN_EXPIRED: a domain has expired (Hostinger says so, or its expiry date is past). \
            DOMAIN_EXPIRING: a domain expires within the next 30 days. \
            LINK_BROKEN: a site is linked to a repository that is not among the user's GitHub repositories \
            (deleted, renamed, or not accessible). \
            SOURCE_NOT_CONNECTED: GitHub or Hostinger is not connected. \
            SOURCE_FAILING: GitHub or Hostinger is connected but not working fully (rate limited, token rejected, \
            unavailable).""")
    enum ProblemKind {
        DEPLOY_FAILED,
        DOMAIN_EXPIRED,
        DOMAIN_EXPIRING,
        LINK_BROKEN,
        SOURCE_NOT_CONNECTED,
        SOURCE_FAILING
    }

    @Schema(name = "OverviewProblem", description = "One thing that needs attention")
    record Problem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProblemKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The source the problem is about: "
                    + "HOSTINGER for deploys and domains, GITHUB for broken links, the source itself for "
                    + "SOURCE_NOT_CONNECTED and SOURCE_FAILING") Source source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "example.com",
                    description = "The site or domain, lower case; null for SOURCE_* problems") String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "octocat/my-site",
                    description = "For LINK_BROKEN: the linked repository (owner/name); null otherwise")
            String repoFullName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "2026-12-01T00:00:00Z",
                    description = "For DEPLOY_FAILED: when the deploy failed; for DOMAIN_EXPIRED and DOMAIN_EXPIRING: "
                            + "the expiry date, null when Hostinger gives none; null otherwise (ISO-8601, UTC)")
            Instant at,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "For the user") String message) {}

    @Schema(name = "OverviewProblems", description = "Everything that needs attention")
    record Problems(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "SOURCE_* problems first (GitHub, "
                    + "then Hostinger), then DEPLOY_FAILED (newest first), DOMAIN_EXPIRED, DOMAIN_EXPIRING (earliest "
                    + "expiry first) and LINK_BROKEN (by domain)") List<Problem> items,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The checks that could not be "
                    + "made, at most one entry per source and code: the items may then be incomplete. Empty when "
                    + "every check could be made") List<SourceProblem> errors) {}

    @Schema(name = "OverviewDeploy", description = "One recent deploy")
    record Deploy(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "example.com",
                    description = "The site's domain, lower case") String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "SUCCEEDED, FAILED, IN_PROGRESS "
                    + "or UNKNOWN (a state Dockline does not know); never NONE") DeployStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2024-05-29T05:51:02Z",
                    description = "When the deploy last changed state (ISO-8601, UTC)") Instant at,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The commit that was deployed; null when not known") Commit commit) {}

    @Schema(name = "OverviewDeploys", description = "The latest deploys across all sites")
    record Deploys(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The latest deploy of each site "
                    + "that has one with a known time, newest first, at most " + Overview.LATEST_DEPLOYS)
            List<Deploy> deploys,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Why deploys may be missing: one "
                    + "entry per Hostinger problem code met (the website list, or one or more sites' deploys); "
                    + "empty when Hostinger answered for every site") List<SourceProblem> errors) {}

    @Schema(name = "Overview", description = "Everything the Overview page shows, in one answer. Each part carries "
            + "its own errors, so one failing source does not hide the parts of the other")
    record Summary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The number of Hostinger websites "
                    + "(as in the Sites list)") Count sites,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The number of GitHub repositories "
                    + "(as in the Repos list)") Count repos,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Problems problems,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Deploys latestDeploys,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The connection state of every "
                    + "source, as in GET /api/status: always GITHUB, then HOSTINGER") List<SourceStatus> sources,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "2026-11-13T09:00:00Z",
                    description = "When the oldest data used was fetched from its source (ISO-8601, UTC), up to 60 s "
                            + "old when saved copies were reused; the time of the request when no source answered")
            Instant fetchedAt) {}
}
