package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.github.GithubApi.Repositories;
import io.github.delucagit.dockline.github.GithubRepositoryList;
import io.github.delucagit.dockline.hostinger.HostingerApi.Domain;
import io.github.delucagit.dockline.hostinger.HostingerApi.Domains;
import io.github.delucagit.dockline.hostinger.HostingerApi.Sites;
import io.github.delucagit.dockline.hostinger.OverviewApi.Count;
import io.github.delucagit.dockline.hostinger.OverviewApi.Deploy;
import io.github.delucagit.dockline.hostinger.OverviewApi.Deploys;
import io.github.delucagit.dockline.hostinger.OverviewApi.Problem;
import io.github.delucagit.dockline.hostinger.OverviewApi.ProblemKind;
import io.github.delucagit.dockline.hostinger.OverviewApi.Problems;
import io.github.delucagit.dockline.hostinger.OverviewApi.Summary;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployStatus;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.SiteDeploy;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SiteLink;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SourceProblem;
import io.github.delucagit.dockline.status.ConnectionState;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.status.SourceStatuses;
import io.github.delucagit.dockline.token.Source;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Everything the Overview page shows, built from the services of the other views, so their saved copies, the
 * Hostinger request cap and their Log entries all apply; nothing here talks to Hostinger or GitHub itself:
 * <ul>
 *   <li>sites: {@link HostingerSiteList} (60-second copy)
 *   <li>repos: {@link GithubRepositoryList} (asks GitHub on every call, as the Repos view does)
 *   <li>domains: {@link HostingerDomainList} (60-second copy), for expired and expiring domains
 *   <li>deploys: {@link SiteDeploys} (20-second copy of the builds), for failed and latest deploys
 *   <li>links: {@link SiteLinks#list()} (five-minute copy of the Git connections), compared with the repos for
 *       broken links
 *   <li>sources: {@link SourceStatuses}, read last so it shows what this call just learned
 * </ul>
 * A failing part never fails the answer: it gets a {@link SourceProblem} and the other parts are still filled.
 * When the website list cannot be read, the deploys and links are not asked for (they need it); the domains are
 * then asked for only when Hostinger failed with {@value HostingerSiteList#UNAVAILABLE}, since a missing or
 * rejected token or a rate limit would fail them the same way.
 */
@Component
class Overview {

    /** The most deploys {@code latestDeploys} lists. */
    static final int LATEST_DEPLOYS = 10;
    /** A domain that expires within this time is "expiring" (the Domains view uses the same 30 days). */
    static final Duration EXPIRING_WITHIN = Duration.ofDays(30);

    private static final Comparator<Deploy> NEWEST_FIRST = Comparator.comparing(Deploy::at).reversed()
            .thenComparing(Deploy::domain);

    private final HostingerSiteList siteList;
    private final HostingerDomainList domainList;
    private final SiteDeploys siteDeploys;
    private final SiteLinks siteLinks;
    private final GithubRepositoryList repositoryList;
    private final SourceStatuses statuses;
    private final Clock clock;

    Overview(HostingerSiteList siteList, HostingerDomainList domainList, SiteDeploys siteDeploys, SiteLinks siteLinks,
            GithubRepositoryList repositoryList, SourceStatuses statuses, Clock clock) {
        this.siteList = siteList;
        this.domainList = domainList;
        this.siteDeploys = siteDeploys;
        this.siteLinks = siteLinks;
        this.repositoryList = repositoryList;
        this.statuses = statuses;
        this.clock = clock;
    }

    /**
     * {@code refresh} skips the saved copies of the website list, the domains and the deploys (as their own
     * endpoints do with {@code refresh=true}); the five-minute Git connections are kept, as in the Sites list.
     */
    Summary summary(boolean refresh) {
        Instant now = clock.instant();
        List<Instant> fetched = new ArrayList<>();
        Errors problemErrors = new Errors();

        // Sites
        Sites sites = null;
        SourceProblem sitesError = null;
        try {
            sites = siteList.list(refresh);
            fetched.add(sites.fetchedAt());
        } catch (ApiProblem e) {
            sitesError = SourceProblem.hostinger(e);
        }
        Count siteCount = sites != null ? new Count(sites.sites().size(), List.of())
                : new Count(null, List.of(sitesError));

        // Repos
        Repositories repos = null;
        Count repoCount;
        try {
            repos = repositoryList.list();
            fetched.add(repos.fetchedAt());
            repoCount = new Count(repos.repositories().size(), List.of());
        } catch (ApiProblem e) {
            SourceProblem error = github(e);
            repoCount = new Count(null, List.of(error));
            problemErrors.add(error);
        }

        List<Problem> deployProblems = new ArrayList<>();
        List<Problem> linkProblems = new ArrayList<>();
        Errors deployErrors = new Errors();
        List<Deploy> latest = new ArrayList<>();
        if (sites == null) {
            deployErrors.add(sitesError);
            problemErrors.add(sitesError);
        } else {
            // Deploys
            try {
                SiteDeployApi.SiteDeploys deploys = siteDeploys.list(refresh);
                fetched.add(deploys.fetchedAt());
                for (SiteDeploy deploy : deploys.deploys()) {
                    deploy.problems().forEach(deployErrors::add);
                    deploy.problems().forEach(problemErrors::add);
                    if (deploy.status() == DeployStatus.FAILED) {
                        deployProblems.add(new Problem(ProblemKind.DEPLOY_FAILED, Source.HOSTINGER, deploy.domain(),
                                null, deploy.at(), "The latest deploy of " + deploy.domain() + " failed."));
                    }
                    if (deploy.status() != DeployStatus.NONE && deploy.at() != null) {
                        latest.add(new Deploy(deploy.domain(), deploy.status(), deploy.at(), deploy.commit()));
                    }
                }
            } catch (ApiProblem e) {
                deployErrors.add(SourceProblem.hostinger(e));
                problemErrors.add(SourceProblem.hostinger(e));
            }
            // Links: broken when the repository is not among the user's repositories. Only when the list is whole,
            // or a repository on a page that was not fetched would look broken.
            SiteLinkApi.SiteLinks links = siteLinks.list();
            links.problems().forEach(problemErrors::add);
            if (repos != null && !repos.truncated()) {
                Set<String> known = repos.repositories().stream()
                        .map(repo -> repo.fullName().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());
                for (SiteLink link : links.links()) {
                    if (link.repo() != null && !known.contains(link.repo().fullName().toLowerCase(Locale.ROOT))) {
                        linkProblems.add(new Problem(ProblemKind.LINK_BROKEN, Source.GITHUB, link.domain(),
                                link.repo().fullName(), null, link.domain() + " is linked to " + link.repo().fullName()
                                        + ", which is not among your GitHub repositories. It may have been deleted "
                                        + "or renamed, or your GitHub account may not have access to it."));
                    }
                }
            }
        }
        deployProblems.sort(Comparator.comparing(Problem::at, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Problem::domain));
        latest.sort(NEWEST_FIRST);

        // Domains
        List<Problem> expired = new ArrayList<>();
        List<Problem> expiring = new ArrayList<>();
        // The site list's error is already in problemErrors.
        boolean askDomains = sitesError == null || HostingerSiteList.UNAVAILABLE.equals(sitesError.code());
        try {
            Domains domains = askDomains ? domainList.list(refresh) : new Domains(List.of(), null);
            if (domains.fetchedAt() != null) {
                fetched.add(domains.fetchedAt());
            }
            for (Domain domain : domains.domains()) {
                if (domain.domain() == null) {
                    continue;
                }
                String name = domain.domain().strip().toLowerCase(Locale.ROOT);
                Instant expires = domain.expiresAt();
                if ("expired".equalsIgnoreCase(domain.status()) || (expires != null && expires.isBefore(now))) {
                    expired.add(new Problem(ProblemKind.DOMAIN_EXPIRED, Source.HOSTINGER, name, null, expires,
                            name + " has expired" + (expires != null ? " (" + date(expires) + ")." : ".")
                                    + " Renew it in hPanel to keep it."));
                } else if (expires != null && !expires.isAfter(now.plus(EXPIRING_WITHIN))) {
                    expiring.add(new Problem(ProblemKind.DOMAIN_EXPIRING, Source.HOSTINGER, name, null, expires,
                            name + " expires on " + date(expires) + "."));
                }
            }
        } catch (ApiProblem e) {
            problemErrors.add(SourceProblem.hostinger(e));
        }

        // Sources, last: the calls above update them at once when they fail.
        List<SourceStatus> sources = statuses.all();
        List<Problem> problems = new ArrayList<>();
        for (SourceStatus status : sources) {
            if (status.state() == ConnectionState.NOT_CONNECTED) {
                problems.add(new Problem(ProblemKind.SOURCE_NOT_CONNECTED, status.source(), null, null, null,
                        name(status.source()) + " is not connected. Connect it in Settings."));
            } else if (status.state() != ConnectionState.CONNECTED) {
                problems.add(new Problem(ProblemKind.SOURCE_FAILING, status.source(), null, null, null,
                        status.message() != null ? status.message() : name(status.source()) + " is not working fully."));
            }
        }
        problems.addAll(deployProblems);
        problems.addAll(expired);
        problems.addAll(expiring);
        linkProblems.sort(Comparator.comparing(Problem::domain));
        problems.addAll(linkProblems);

        return new Summary(siteCount, repoCount, new Problems(List.copyOf(problems), problemErrors.list()),
                new Deploys(List.copyOf(latest.subList(0, Math.min(LATEST_DEPLOYS, latest.size()))),
                        deployErrors.list()),
                sources, fetched.stream().min(Comparator.naturalOrder()).orElse(now));
    }

    private static SourceProblem github(ApiProblem problem) {
        String retryAfter = problem.retryAfter();
        return new SourceProblem(Source.GITHUB, problem.code(), problem.getMessage(),
                retryAfter != null && retryAfter.matches("\\d{1,9}") ? Long.parseLong(retryAfter) : null);
    }

    private static String name(Source source) {
        return source == Source.GITHUB ? "GitHub" : "Hostinger";
    }

    private static String date(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** Source problems, at most one per source and code (the first one met). */
    private static final class Errors {
        private final Map<String, SourceProblem> byKey = new LinkedHashMap<>();

        void add(SourceProblem problem) {
            byKey.putIfAbsent(problem.source() + "/" + problem.code(), problem);
        }

        List<SourceProblem> list() {
            return List.copyOf(byKey.values());
        }
    }
}
