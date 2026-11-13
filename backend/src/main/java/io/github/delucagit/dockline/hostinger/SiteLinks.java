package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.github.GithubRepositoryCheck;
import io.github.delucagit.dockline.hostinger.HostingerApi.Site;
import io.github.delucagit.dockline.hostinger.HostingerGitConnections.Connection;
import io.github.delucagit.dockline.hostinger.HostingerGitConnections.Mode;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.LinkSource;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.Repo;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.RepoWarning;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.RepoWarningCode;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SiteDetail;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SiteLink;
import io.github.delucagit.dockline.hostinger.SiteLinkApi.SourceProblem;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.settings.SettingsUnavailableException;
import io.github.delucagit.dockline.settings.SiteRepoLinkRepository;
import io.github.delucagit.dockline.token.Source;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Which GitHub repository belongs to a site. Two sources are combined:
 * <ol>
 *   <li>Hostinger's Git connection of the site ({@link HostingerGitConnections}); it always wins.
 *   <li>The manual link the user saved ({@link SiteRepoLinkRepository}). While a Hostinger Git connection
 *       exists, the manual link is not shown, but it is kept and shows again when the connection is removed.
 * </ol>
 * A site is identified by its domain (case does not matter).
 *
 * <p>One failing source does not break the other. When Hostinger cannot be asked, {@link #detail} and
 * {@link #list} still answer from the saved manual links and say so ({@code hostingerChecked} false plus a
 * {@link SourceProblem}); when GitHub cannot be asked, the link is still shown, without the repository check.
 * Only {@link #link} needs Hostinger: it refuses to save while it cannot tell whether the site is Git-connected.
 */
@Component
class SiteLinks {

    static final String SITE_NOT_FOUND = "SITE_NOT_FOUND";
    static final String GIT_CONNECTED = "SITE_GIT_CONNECTED";
    static final String INVALID_REPOSITORY = "INVALID_REPOSITORY";
    static final String SETTINGS_UNAVAILABLE = "SETTINGS_UNAVAILABLE";
    /**
     * {@link #list} stops asking Hostinger about further sites once it has sent this many requests in one call
     * (a site costs one or two); the sites left over are answered in a later call. Together with the website
     * list this stays under the cap of {@link HostingerRateLimiter#MAX_REQUESTS} a minute, so opening the Sites
     * view never makes a request wait.
     */
    static final int MAX_REQUESTS_PER_LIST = 40;

    private final HostingerSiteList siteList;
    private final HostingerGitConnections connections;
    private final HostingerClient client;
    private final SiteRepoLinkRepository manualLinks;
    private final GithubRepositoryCheck repositoryCheck;
    private final ProblemLog problemLog;

    SiteLinks(HostingerSiteList siteList, HostingerGitConnections connections, HostingerClient client,
            SiteRepoLinkRepository manualLinks, GithubRepositoryCheck repositoryCheck, ProblemLog problemLog) {
        this.siteList = siteList;
        this.connections = connections;
        this.client = client;
        this.manualLinks = manualLinks;
        this.repositoryCheck = repositoryCheck;
        this.problemLog = problemLog;
    }

    /**
     * The site with its linked repository, checked against GitHub. {@code refresh} asks Hostinger again about
     * this site's Git connection. Throws 404 {@value #SITE_NOT_FOUND} when Hostinger answers and has no such
     * website.
     */
    SiteDetail detail(String rawDomain, boolean refresh) {
        String domain = requested(rawDomain);
        List<SourceProblem> problems = new ArrayList<>();
        Optional<String> connected = Optional.empty();
        boolean checked = false;
        try {
            connected = connections.find(site(domain), refresh ? Mode.REFRESH : Mode.NORMAL).repository();
            checked = true;
        } catch (ApiProblem problem) {
            if (problem.code().equals(SITE_NOT_FOUND)) {
                throw problem;
            }
            problems.add(SourceProblem.hostinger(problem));
        }
        SiteLink link = resolve(domain, connected, checked, manualLinks.find(domain));
        if (link.repo() == null) {
            return new SiteDetail(domain, null, link.source(), checked, null, List.copyOf(problems));
        }
        String fullName = link.repo().fullName();
        GithubRepositoryCheck.Result result = repositoryCheck.check(fullName);
        RepoWarning warning = switch (result.outcome()) {
            case NOT_FOUND -> new RepoWarning(RepoWarningCode.REPO_NOT_FOUND, fullName + " was not found on GitHub. "
                    + "It may have been deleted or renamed, or your GitHub account may not have access to it.");
            case NOT_ACCESSIBLE -> new RepoWarning(RepoWarningCode.REPO_NOT_ACCESSIBLE,
                    "GitHub does not allow access to " + fullName + ".");
            case MOVED -> new RepoWarning(RepoWarningCode.REPO_MOVED,
                    fullName + " was renamed or moved on GitHub.");
            case FOUND, UNKNOWN -> null;
        };
        if (result.outcome() == GithubRepositoryCheck.Outcome.UNKNOWN) {
            problems.add(new SourceProblem(Source.GITHUB, result.problemCode(), result.problemMessage(),
                    result.retryAfterSeconds()));
        }
        Repo repo = result.htmlUrl() == null ? link.repo() : new Repo(fullName, result.htmlUrl());
        return new SiteDetail(domain, repo, link.source(), checked, warning, List.copyOf(problems));
    }

    /**
     * The link of every site, for the Sites list. GitHub is not asked. Hostinger is asked about at most
     * {@value #MAX_REQUESTS_PER_LIST} requests' worth of sites per call, and not at all for sites whose answer
     * is saved (five minutes); it stops at the first Hostinger failure. Sites it did not get to have
     * {@code hostingerChecked} false.
     */
    SiteLinkApi.SiteLinks list() {
        Map<String, String> manual = manualLinks.findAll();
        List<SourceProblem> problems = new ArrayList<>();
        List<Site> sites;
        try {
            sites = siteList.list(false).sites();
        } catch (ApiProblem problem) {
            problems.add(SourceProblem.hostinger(problem));
            List<SiteLink> saved = manual.entrySet().stream()
                    .map(entry -> resolve(entry.getKey(), Optional.empty(), false, Optional.of(entry.getValue())))
                    .toList();
            return new SiteLinkApi.SiteLinks(saved, false, List.copyOf(problems));
        }
        long sentAtStart = client.sentCount();
        boolean ask = true;
        // By domain: Hostinger lists a domain once, but nothing here depends on that.
        Map<String, SiteLink> links = new LinkedHashMap<>();
        for (Site site : sites) {
            if (site.domain() == null || links.containsKey(normalize(site.domain()))) {
                continue;
            }
            String domain = normalize(site.domain());
            ask = ask && client.sentCount() - sentAtStart < MAX_REQUESTS_PER_LIST;
            Connection connection = Connection.NOT_CHECKED;
            try {
                connection = connections.find(site, ask ? Mode.NORMAL : Mode.SAVED_ONLY);
            } catch (ApiProblem problem) {
                problems.add(SourceProblem.hostinger(problem));
                ask = false;
            }
            links.put(domain, resolve(domain, connection.repository(), connection.checked(),
                    Optional.ofNullable(manual.get(domain))));
        }
        List<SiteLink> all = List.copyOf(links.values());
        return new SiteLinkApi.SiteLinks(all, all.stream().allMatch(SiteLink::hostingerChecked), List.copyOf(problems));
    }

    /**
     * Saves a manual link (replacing an earlier one) and returns the site as {@link #detail} does. Hostinger is
     * asked afresh whether the site is Git-connected; if it is, or if Hostinger cannot be asked, nothing is saved.
     */
    SiteDetail link(String rawDomain, String rawRepoFullName) {
        String domain = requested(rawDomain);
        String repoFullName = rawRepoFullName == null ? "" : rawRepoFullName.strip();
        if (!GithubRepositoryCheck.isFullName(repoFullName)) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, INVALID_REPOSITORY, "Not a GitHub repository name",
                    "Give the repository as owner/name, for example octocat/my-site.");
        }
        Optional<String> connected = connections.find(site(domain), Mode.REFRESH).repository();
        if (connected.isPresent()) {
            throw new ApiProblem(HttpStatus.CONFLICT, GIT_CONNECTED, "The site is Git-connected in Hostinger",
                    domain + " is connected to " + connected.get() + " in Hostinger's Git settings. That connection "
                            + "is always shown, so a manual link cannot be set. Remove the Git connection in hPanel "
                            + "first if you want to link another repository.");
        }
        try {
            manualLinks.save(domain, repoFullName);
        } catch (SettingsUnavailableException e) {
            throw settingsUnavailable("PUT /api/sites/" + domain + "/repo", e);
        }
        return detail(domain, false);
    }

    /** Removes the site's manual link, if it has one. Needs neither Hostinger nor GitHub. */
    void unlink(String rawDomain) {
        String domain = requested(rawDomain);
        try {
            manualLinks.remove(domain);
        } catch (SettingsUnavailableException e) {
            throw settingsUnavailable("DELETE /api/sites/" + domain + "/repo", e);
        }
    }

    /** Hostinger's website with this domain; throws the website list's problems, or 404 when there is none. */
    private Site site(String domain) {
        return siteList.list(false).sites().stream()
                .filter(site -> site.domain() != null && normalize(site.domain()).equals(domain))
                .findFirst()
                .orElseThrow(() -> new ApiProblem(HttpStatus.NOT_FOUND, SITE_NOT_FOUND, "No such site",
                        "Hostinger has no website with the domain " + domain + "."));
    }

    private static SiteLink resolve(String domain, Optional<String> connected, boolean checked,
            Optional<String> manual) {
        if (connected.isPresent()) {
            return new SiteLink(domain, repo(connected.get()), LinkSource.HOSTINGER, checked);
        }
        return manual.map(fullName -> new SiteLink(domain, repo(fullName), LinkSource.MANUAL, checked))
                .orElseGet(() -> new SiteLink(domain, null, LinkSource.NONE, checked));
    }

    private static Repo repo(String fullName) {
        return new Repo(fullName, GithubRepositoryCheck.htmlUrl(fullName));
    }

    private ApiProblem settingsUnavailable(String action, SettingsUnavailableException e) {
        problemLog.report(LogLevel.ERROR, LogSource.BACKEND, action, 500, e.getMessage(), action, null, e);
        return new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, SETTINGS_UNAVAILABLE, "The link could not be saved",
                "Dockline could not write its settings file, so nothing was changed. See the Log section.");
    }

    /** The domain from the request path, lower case; 404 for a blank one. */
    private static String requested(String rawDomain) {
        String domain = normalize(rawDomain);
        if (domain.isEmpty()) {
            throw new ApiProblem(HttpStatus.NOT_FOUND, SITE_NOT_FOUND, "No such site", "No domain was given.");
        }
        return domain;
    }

    private static String normalize(String domain) {
        return domain == null ? "" : domain.strip().toLowerCase(Locale.ROOT);
    }
}
