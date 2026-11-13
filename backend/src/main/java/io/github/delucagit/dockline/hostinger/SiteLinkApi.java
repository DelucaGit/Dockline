package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.token.Source;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The request and response shapes of the site–repo link endpoints ({@code /api/sites/...}). */
final class SiteLinkApi {

    private SiteLinkApi() {}

    @Schema(name = "SiteLinkSource", enumAsRef = true, description = """
            Where a site's repository link comes from. \
            HOSTINGER: the site is Git-connected in Hostinger (hPanel's Git section or Git auto-deployment); \
            this always wins and cannot be changed in Dockline. \
            MANUAL: linked by the user in Dockline (saved in the settings file). \
            NONE: no repository is linked.""")
    enum LinkSource {
        HOSTINGER,
        MANUAL,
        NONE
    }

    @Schema(name = "SiteRepo", description = "The GitHub repository linked to a site")
    record Repo(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "octocat/my-site",
                    description = "owner/name") String fullName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "https://github.com/octocat/my-site",
                    description = "The repository's page on GitHub") String htmlUrl) {}

    @Schema(name = "SiteRepoWarningCode", enumAsRef = true, description = """
            REPO_NOT_FOUND: GitHub does not know the repository (deleted, renamed away, or private and not \
            visible to the connected GitHub user). \
            REPO_NOT_ACCESSIBLE: GitHub refuses access to it. \
            REPO_MOVED: it was renamed or transferred.""")
    enum RepoWarningCode {
        REPO_NOT_FOUND,
        REPO_NOT_ACCESSIBLE,
        REPO_MOVED
    }

    @Schema(name = "SiteRepoWarning", description = "The linked repository is missing or not accessible on GitHub; "
            + "the link itself is kept")
    record RepoWarning(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) RepoWarningCode code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "For the user") String message) {}

    @Schema(name = "SiteSourceProblem", description = "One source (Hostinger or GitHub) could not be asked; the rest "
            + "of the answer is still valid")
    record SourceProblem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Source source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "GITHUB_NOT_CONNECTED",
                    description = "The same stable codes as the problem responses of the list endpoints: "
                            + "HOSTINGER_NOT_CONNECTED, HOSTINGER_TOKEN_REJECTED, HOSTINGER_RATE_LIMITED, "
                            + "HOSTINGER_UNAVAILABLE, GITHUB_NOT_CONNECTED, GITHUB_TOKEN_REJECTED, "
                            + "GITHUB_RATE_LIMITED, GITHUB_UNAVAILABLE") String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "For the user") String message,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, example = "42",
                    description = "For the RATE_LIMITED codes: seconds to wait, when known; otherwise null")
            Long retryAfterSeconds) {

        static SourceProblem hostinger(ApiProblem problem) {
            String retryAfter = problem.retryAfter();
            return new SourceProblem(Source.HOSTINGER, problem.code(), problem.getMessage(),
                    retryAfter != null && retryAfter.matches("\\d{1,9}") ? Long.parseLong(retryAfter) : null);
        }
    }

    @Schema(name = "SiteDetail", description = "One site and the GitHub repository linked to it")
    record SiteDetail(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "example.com",
                    description = "The site's domain, lower case") String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The linked repository; null when source is NONE") Repo repo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LinkSource source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "False when Hostinger could not be "
                    + "asked whether the site is Git-connected (see problems): source is then MANUAL or NONE from "
                    + "the saved manual links alone") boolean hostingerChecked,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true, description = "Set when GitHub "
                    + "says the linked repository is missing or not accessible; null when it is fine, when there is "
                    + "no repository, or when GitHub could not be asked (see problems)") RepoWarning repoWarning,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "At most one entry per source that "
                    + "could not be asked; empty when both answered") List<SourceProblem> problems) {}

    @Schema(name = "SiteLink", description = "One site's repository link, for the Sites list")
    record SiteLink(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "example.com",
                    description = "The site's domain, lower case") String domain,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true,
                    description = "The linked repository; null when source is NONE") Repo repo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LinkSource source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "False when Hostinger was not asked "
                    + "about this site in this call: source is then MANUAL or NONE from the saved manual links "
                    + "alone, and a later call fills it in") boolean hostingerChecked) {}

    @Schema(name = "SiteLinks", description = "The repository link of every site, for the Sites list")
    record SiteLinks(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "One entry per Hostinger website "
                    + "that has a domain, sorted by domain; when Hostinger could not list the websites, one "
                    + "entry per saved manual link instead") List<SiteLink> links,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "True when every entry has "
                    + "hostingerChecked true") boolean complete,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Set when Hostinger could not be "
                    + "asked (GitHub is never asked for this list); empty otherwise") List<SourceProblem> problems) {}

    @Schema(name = "SiteRepoRequest", description = "The repository to link a site to")
    record RepoRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "octocat/my-site",
                    description = "GitHub full name, owner/name; surrounding whitespace is ignored")
            String repoFullName) {}
}
