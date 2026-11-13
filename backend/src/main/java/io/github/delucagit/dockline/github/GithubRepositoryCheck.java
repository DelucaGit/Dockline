package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.time.Clock;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Checks that one repository still exists and is visible to the user, with GitHub's
 * {@code GET /repos/{owner}/{repo}}. Never throws for a GitHub failure: the answer is a {@link Result}, so a
 * caller that also shows data from another source (a site and its linked repository) keeps working.
 *
 * <pre>{@code
 * Result result = check.check("octocat/my-site");
 * if (result.outcome() == Outcome.NOT_FOUND) { ... show a warning ... }
 * }</pre>
 *
 * <ul>
 *   <li>200: {@link Outcome#FOUND} with the repository's page on GitHub
 *   <li>404: {@link Outcome#NOT_FOUND} (deleted, or private and hidden from the user); also for a name that
 *       cannot be a GitHub repository, without asking GitHub
 *   <li>403 that is not a rate limit, or 451: {@link Outcome#NOT_ACCESSIBLE}
 *   <li>301: {@link Outcome#MOVED} (renamed or transferred)
 *   <li>anything else: {@link Outcome#UNKNOWN} with one of the {@code GITHUB_*} codes of
 *       {@link GithubRepositoryList} and a message for the user. Like there, these failures are written to the
 *       Log section and update the GitHub status at once (not for "not connected": GitHub is not called).
 * </ul>
 */
@Component
public class GithubRepositoryCheck {

    /** A GitHub owner (user or organization) and a repository name, as GitHub allows them. */
    private static final Pattern FULL_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}");
    static final String ACTION = "GET /repos/{owner}/{repo}";

    private final TokenStore tokenStore;
    private final GithubClient client;
    private final GithubStatusProvider status;
    private final ProblemLog problemLog;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    GithubRepositoryCheck(TokenStore tokenStore, GithubClient client, GithubStatusProvider status,
            ProblemLog problemLog, Clock clock) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.status = status;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    /** What GitHub said about the repository. */
    public enum Outcome {
        FOUND,
        NOT_FOUND,
        NOT_ACCESSIBLE,
        MOVED,
        /** GitHub could not be asked; see {@link Result#problemCode()}. */
        UNKNOWN
    }

    /**
     * @param htmlUrl           the repository's page on GitHub, for {@link Outcome#FOUND}; otherwise null
     * @param problemCode       for {@link Outcome#UNKNOWN}: GITHUB_NOT_CONNECTED, GITHUB_TOKEN_REJECTED,
     *                          GITHUB_RATE_LIMITED or GITHUB_UNAVAILABLE; otherwise null
     * @param problemMessage    for {@link Outcome#UNKNOWN}: what happened, for the user; otherwise null
     * @param retryAfterSeconds for GITHUB_RATE_LIMITED when GitHub said how long to wait; otherwise null
     */
    public record Result(Outcome outcome, String htmlUrl, String problemCode, String problemMessage,
            Long retryAfterSeconds) {

        static Result of(Outcome outcome) {
            return new Result(outcome, null, null, null, null);
        }

        static Result problem(String code, String message, Long retryAfterSeconds) {
            return new Result(Outcome.UNKNOWN, null, code, message, retryAfterSeconds);
        }
    }

    /** True when the text can be a GitHub repository full name ({@code owner/name}). */
    public static boolean isFullName(String text) {
        return text != null && FULL_NAME.matcher(text).matches() && !text.endsWith("/.") && !text.endsWith("/..");
    }

    /** The repository's page on GitHub, built from its full name (GitHub is not asked). */
    public static String htmlUrl(String fullName) {
        return "https://github.com/" + fullName;
    }

    public Result check(String fullName) {
        if (!isFullName(fullName)) {
            return Result.of(Outcome.NOT_FOUND);
        }
        Optional<String> saved = tokenStore.read(Source.GITHUB);
        if (saved.isEmpty()) {
            return Result.problem(GithubRepositoryList.NOT_CONNECTED,
                    "GitHub is not connected, so the repository could not be checked. Connect to GitHub in Settings.",
                    null);
        }
        String token = saved.get();
        String request = "GET " + client.repositoryUrl(fullName) + " (with the saved token)";
        GithubClient.Reply reply;
        try {
            reply = client.getRepository(fullName, token);
        } catch (RestClientException e) {
            SourceStatus unreachable = GithubStatusProvider.unreachable(e);
            status.observed(token, unreachable);
            problemLog.report(LogLevel.WARN, LogSource.GITHUB, ACTION, null, unreachable.message(), request, null, e);
            return Result.problem(GithubRepositoryList.UNAVAILABLE,
                    "Could not reach GitHub, so the repository could not be checked.", null);
        }
        int code = reply.status();
        if (reply.ok()) {
            return new Result(Outcome.FOUND, pageOf(reply.body(), fullName), null, null, null);
        }
        if (code == 404) {
            return Result.of(Outcome.NOT_FOUND);
        }
        if (code == 301) {
            return Result.of(Outcome.MOVED);
        }
        if (code == 401) {
            status.observed(token, GithubStatusProvider.revoked());
            problemLog.report(LogLevel.ERROR, LogSource.GITHUB, ACTION, code, GithubStatusProvider.REVOKED, request,
                    reply.body(), null);
            return Result.problem(GithubRepositoryList.TOKEN_REJECTED, GithubStatusProvider.REVOKED, null);
        }
        if (GithubStatusProvider.isRateLimit(reply)) {
            SourceStatus limited = GithubStatusProvider.rateLimited();
            status.observed(token, limited);
            problemLog.report(LogLevel.WARN, LogSource.GITHUB, ACTION, code, limited.message(), request, reply.body(),
                    null);
            return Result.problem(GithubRepositoryList.RATE_LIMITED, limited.message(), retryAfter(reply));
        }
        if (code == 403 || code == 451) {
            return Result.of(Outcome.NOT_ACCESSIBLE);
        }
        SourceStatus failing = GithubStatusProvider.failing(code);
        status.observed(token, failing);
        problemLog.report(LogLevel.WARN, LogSource.GITHUB, ACTION, code, failing.message(), request, reply.body(), null);
        return Result.problem(GithubRepositoryList.UNAVAILABLE,
                failing.message() + " The repository could not be checked.", null);
    }

    /** GitHub's {@code html_url}; the address built from the name when the answer has none. */
    private String pageOf(String body, String fullName) {
        try {
            String url = json.readTree(body).path("html_url").asString(null);
            return url == null || !url.startsWith("https://") ? htmlUrl(fullName) : url;
        } catch (JacksonException | IllegalArgumentException e) {
            return htmlUrl(fullName);
        }
    }

    /** GitHub's Retry-After, else the seconds until x-ratelimit-reset (epoch seconds); null when neither is usable. */
    private Long retryAfter(GithubClient.Reply reply) {
        String retryAfter = reply.headers().getFirst("retry-after");
        if (retryAfter != null && retryAfter.strip().matches("\\d{1,9}")) {
            return Long.parseLong(retryAfter.strip());
        }
        try {
            String reset = reply.headers().getFirst("x-ratelimit-reset");
            return reset == null ? null : Math.max(1, Long.parseLong(reset.strip()) - clock.instant().getEpochSecond());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
