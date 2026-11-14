package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.github.GithubRepositoryCheck;
import io.github.delucagit.dockline.hostinger.HostingerApi.Site;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.status.ConnectionState;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Finds the GitHub repository a website is Git-connected to in Hostinger. Hostinger has two kinds of Git
 * connection, on two endpoints, and neither lists the other's:
 * <ol>
 *   <li>auto-deployment through Hostinger's GitHub app,
 *       {@code GET .../accounts/{username}/websites/{domain}/git/auto-deployments/settings}:
 *       {@code {installation_uuid, is_enabled, owner, repository, branch, directory}}, every field null when
 *       not set up. A paused one ({@code is_enabled} false) still counts. The app also exists for GitLab, so
 *       the provider is looked up in {@code GET /api/hosting/v1/git/installations}; an installation that is
 *       not listed there (suspended) is taken to be GitHub.
 *   <li>repositories cloned in hPanel's Git section, {@code GET .../git/repositories}: an array of
 *       {@code {repository_url, branch, directory, webhook}}; see {@link #githubFullName} for the URL forms.
 *       With several GitHub repositories, the one in the document root (empty {@code directory}) wins, else
 *       the first.
 * </ol>
 * The auto-deployment is asked first and wins; the second endpoint is only asked when the first has no GitHub
 * repository, so a site costs one or two Hostinger requests, and none while the answers are saved
 * ({@link HostingerClient#GIT_TTL}).
 *
 * <p>Not Git-connected (an empty result, never an error): a website without a username or domain (it cannot
 * be asked), a repository on another host such as GitLab or Bitbucket (Dockline only links GitHub
 * repositories, so such a site can still get a manual link), and a website for which Hostinger answers 404 or
 * 422 (no Git on that kind of website).
 *
 * <p>Failures become an {@link ApiProblem} with the same codes, Log entries and status updates as
 * {@link HostingerSiteList}.
 */
@Component
class HostingerGitConnections {

    /** A username or domain that can go into a URL path as it is. Anything else is not asked about. */
    private static final Pattern PATH_SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,252}");

    /**
     * github.com clone addresses: {@code https://github.com/owner/name(.git)}, the same with credentials
     * ({@code https://***@github.com/...}, {@code https://***:***@github.com/...}), {@code git://},
     * {@code ssh://git@github.com(:22)/owner/name.git}, {@code ssh://git@ssh.github.com:443/...} and the short
     * {@code git@github.com:owner/name.git}.
     */
    private static final Pattern GITHUB_URL = Pattern.compile(
            "^(?:(?:https?|git|ssh)://)?(?:[^@/\\s]+@)?(?:www\\.|ssh\\.)?github\\.com(?::\\d+)?[:/]"
                    + "([A-Za-z0-9][A-Za-z0-9-]*)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$",
            Pattern.CASE_INSENSITIVE);

    private final TokenStore tokenStore;
    private final HostingerClient client;
    private final HostingerStatusProvider status;
    private final ProblemLog problemLog;
    private final ObjectMapper json = new ObjectMapper();

    HostingerGitConnections(TokenStore tokenStore, HostingerClient client, HostingerStatusProvider status,
            ProblemLog problemLog) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.status = status;
        this.problemLog = problemLog;
    }

    /** How to get Hostinger's answers. */
    enum Mode {
        /** Reuse saved answers; ask Hostinger for the rest. */
        NORMAL,
        /** Ask Hostinger again, skipping saved answers. */
        REFRESH,
        /** Only saved answers; Hostinger is never asked. */
        SAVED_ONLY
    }

    /**
     * @param checked false when {@link Mode#SAVED_ONLY} had no saved answer: nothing is known about the site
     * @param repository the GitHub repository ({@code owner/name}) the site is Git-connected to, if any
     */
    record Connection(boolean checked, Optional<String> repository) {
        static final Connection NOT_CHECKED = new Connection(false, Optional.empty());
        static final Connection NONE = new Connection(true, Optional.empty());
    }

    /** The site's Git connection; throws {@link ApiProblem} when Hostinger cannot be asked (see the class comment). */
    Connection find(Site site, Mode mode) {
        String token = tokenStore.read(Source.HOSTINGER).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT,
                HostingerSiteList.NOT_CONNECTED, "Hostinger is not connected",
                "Connect to Hostinger in Settings to see which sites are Git-connected."));
        if (site.username() == null || site.domain() == null || !PATH_SAFE.matcher(site.username()).matches()
                || !PATH_SAFE.matcher(site.domain()).matches()) {
            return Connection.NONE;
        }
        try {
            Optional<String> repository = autoDeployment(site, token, mode);
            if (repository.isEmpty()) {
                repository = clonedRepository(site, token, mode);
            }
            return new Connection(true, repository);
        } catch (NotSaved e) {
            return Connection.NOT_CHECKED;
        }
    }

    /**
     * The {@code owner/name} of a github.com clone address, in any of the forms of {@link #GITHUB_URL};
     * empty for another host, or anything that is not a repository address.
     */
    static Optional<String> githubFullName(String repositoryUrl) {
        if (repositoryUrl == null) {
            return Optional.empty();
        }
        Matcher matcher = GITHUB_URL.matcher(repositoryUrl.strip());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String fullName = matcher.group(1) + "/" + matcher.group(2);
        return GithubRepositoryCheck.isFullName(fullName) ? Optional.of(fullName) : Optional.empty();
    }

    private Optional<String> autoDeployment(Site site, String token, Mode mode) {
        String path = HostingerClient.gitAutoDeploymentPath(site.username(), site.domain());
        Optional<HostingerClient.Reply> reply = get(path, token, mode);
        if (reply.isEmpty()) {
            return Optional.empty();
        }
        JsonNode settings = read(reply.get(), path, token);
        if (!settings.isObject()) {
            throw unreadable(reply.get(), path, token, "not an object");
        }
        String owner = settings.path("owner").asString(null);
        String repository = settings.path("repository").asString(null);
        if (owner == null || repository == null || !GithubRepositoryCheck.isFullName(owner + "/" + repository)) {
            return Optional.empty();
        }
        String installation = settings.path("installation_uuid").asString(null);
        if (installation != null && !isGithubInstallation(installation, token, mode)) {
            return Optional.empty();
        }
        return Optional.of(owner + "/" + repository);
    }

    /** False only when Hostinger lists the installation with another provider (gitlab, bitbucket). */
    private boolean isGithubInstallation(String uuid, String token, Mode mode) {
        String path = HostingerClient.GIT_INSTALLATIONS_PATH;
        // The list of installations is shared by all sites: a refresh of one site reuses a saved copy.
        Optional<HostingerClient.Reply> reply = get(path, token, mode == Mode.REFRESH ? Mode.NORMAL : mode);
        if (reply.isEmpty()) {
            return true;
        }
        JsonNode installations = read(reply.get(), path, token);
        if (!installations.isArray()) {
            throw unreadable(reply.get(), path, token, "not a list");
        }
        for (JsonNode installation : installations) {
            if (uuid.equals(installation.path("uuid").asString(null))) {
                String provider = installation.path("provider").asString(null);
                return provider == null || provider.equalsIgnoreCase("github");
            }
        }
        return true;
    }

    private Optional<String> clonedRepository(Site site, String token, Mode mode) {
        String path = HostingerClient.gitRepositoriesPath(site.username(), site.domain());
        Optional<HostingerClient.Reply> reply = get(path, token, mode);
        if (reply.isEmpty()) {
            return Optional.empty();
        }
        JsonNode repositories = read(reply.get(), path, token);
        if (!repositories.isArray()) {
            throw unreadable(reply.get(), path, token, "not a list");
        }
        Optional<String> first = Optional.empty();
        for (JsonNode repository : repositories) {
            Optional<String> fullName = githubFullName(repository.path("repository_url").asString(null));
            if (fullName.isPresent() && repository.path("directory").asString("").isEmpty()) {
                return fullName;
            }
            if (first.isEmpty()) {
                first = fullName;
            }
        }
        return first;
    }

    /** Hostinger's successful answer; empty when it says the website has no Git at all (404 or 422). */
    private Optional<HostingerClient.Reply> get(String path, String token, Mode mode) {
        if (mode == Mode.SAVED_ONLY) {
            HostingerClient.Reply saved = client.savedGit(path, token).orElseThrow(NotSaved::new);
            return saved.noGit() ? Optional.empty() : Optional.of(saved);
        }
        HostingerClient.Reply reply;
        try {
            reply = client.getGit(path, token, mode == Mode.REFRESH);
        } catch (HostingerRateLimitedException e) {
            // The client already logged the 429; the status dot reads the same wait from the limiter.
            throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, HostingerSiteList.RATE_LIMITED,
                    "Hostinger rate limit reached", HostingerStatusProvider.rateLimited(e.retryAfter()).message(),
                    Long.toString(e.retryAfterSeconds()));
        } catch (RestClientException e) {
            SourceStatus unreachable = HostingerStatusProvider.unreachable(e);
            status.observed(token, unreachable);
            problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, "GET " + path, null, unreachable.message(),
                    request(path), null, e);
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, HostingerSiteList.UNAVAILABLE, "Hostinger is unavailable",
                    HostingerStatusProvider.noAnswerMessage(e));
        }
        if (reply.ok()) {
            return Optional.of(reply);
        }
        int code = reply.status();
        if (reply.noGit()) {
            return Optional.empty();
        }
        if (reply.tokenRejected()) {
            status.observed(token, HostingerStatusProvider.revoked());
            problemLog.report(LogLevel.ERROR, LogSource.HOSTINGER, "GET " + path, code, HostingerStatusProvider.REVOKED,
                    request(path), reply.body(), null);
            throw new ApiProblem(HttpStatus.CONFLICT, HostingerSiteList.TOKEN_REJECTED, "Hostinger rejected the token",
                    HostingerStatusProvider.REVOKED);
        }
        SourceStatus failing = HostingerStatusProvider.failing(code);
        throw unavailable(token, code, path, reply.body(), failing.message(), failing);
    }

    private JsonNode read(HostingerClient.Reply reply, String path, String token) {
        try {
            return json.readTree(reply.body());
        } catch (JacksonException e) {
            throw unreadable(reply, path, token, e.getMessage());
        }
    }

    private ApiProblem unreadable(HostingerClient.Reply reply, String path, String token, String why) {
        return unavailable(token, reply.status(), path, reply.body(),
                "Hostinger's Git settings of a website could not be read (" + why + ").",
                new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED,
                        "Hostinger sent an answer Dockline could not read."));
    }

    private ApiProblem unavailable(String token, Integer code, String path, String response, String message,
            SourceStatus newStatus) {
        status.observed(token, newStatus);
        problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, "GET " + path, code, message, request(path), response,
                null);
        return new ApiProblem(HttpStatus.BAD_GATEWAY, HostingerSiteList.UNAVAILABLE, "Hostinger is unavailable",
                message + " The site's Git connection could not be loaded; try again later.");
    }

    private static String request(String path) {
        return "GET " + path + " (with the saved token)";
    }

    /** {@link Mode#SAVED_ONLY} found no saved answer. */
    private static final class NotSaved extends RuntimeException {
        NotSaved() {
            super(null, null, false, false);
        }
    }
}
