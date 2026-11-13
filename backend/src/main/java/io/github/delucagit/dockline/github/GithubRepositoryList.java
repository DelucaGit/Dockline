package io.github.delucagit.dockline.github;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.github.GithubApi.Repositories;
import io.github.delucagit.dockline.github.GithubApi.Repository;
import io.github.delucagit.dockline.github.GithubApi.Visibility;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.status.ConnectionState;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Lists the user's GitHub repositories with {@code GET /user/repos}, following every {@code next} page link
 * (up to {@link #MAX_PAGES}). Nothing is cached: every call asks GitHub.
 *
 * <p>Failures become an {@link ApiProblem} with a stable {@code code}; each one is written to the Log section
 * (the user asked for the list, so one entry per failed request) and fed into {@link GithubStatusProvider},
 * so the GitHub dot changes at once:
 * <ul>
 *   <li>no token: 409 {@value #NOT_CONNECTED} (GitHub is not called, nothing logged)
 *   <li>401: 409 {@value #TOKEN_REJECTED}, status ERROR
 *   <li>rate limit (429, or 403 with no requests left / Retry-After): 429 {@value #RATE_LIMITED}, status LIMITED
 *   <li>other 403, 5xx or anything unexpected, unreachable, timeout, unreadable answer, or a next-page link to
 *       another host: 502 {@value #UNAVAILABLE}, status LIMITED
 * </ul>
 */
@Component
class GithubRepositoryList {

    /** 50 pages of 100: 5,000 repositories. */
    static final int MAX_PAGES = 50;
    static final String NOT_CONNECTED = "GITHUB_NOT_CONNECTED";
    static final String TOKEN_REJECTED = "GITHUB_TOKEN_REJECTED";
    static final String RATE_LIMITED = "GITHUB_RATE_LIMITED";
    static final String UNAVAILABLE = "GITHUB_UNAVAILABLE";
    static final String ACTION = "GET /user/repos";

    private static final Comparator<Repository> NEWEST_PUSH_FIRST = Comparator
            .comparing(Repository::lastPushAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Repository::fullName, String.CASE_INSENSITIVE_ORDER);

    private final TokenStore tokenStore;
    private final GithubClient client;
    private final GithubStatusProvider status;
    private final ProblemLog problemLog;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    GithubRepositoryList(TokenStore tokenStore, GithubClient client, GithubStatusProvider status,
            ProblemLog problemLog, Clock clock) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.status = status;
        this.problemLog = problemLog;
        this.clock = clock;
    }

    Repositories list() {
        String token = tokenStore.read(Source.GITHUB).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT,
                NOT_CONNECTED, "GitHub is not connected", "Connect to GitHub in Settings to see your repositories."));
        Instant fetchedAt = clock.instant();
        List<Repository> repositories = new ArrayList<>();
        URI page = URI.create(client.repositoriesUrl());
        boolean truncated = false;
        for (int pages = 0; page != null; pages++) {
            if (pages == MAX_PAGES) {
                truncated = true;
                problemLog.report(LogLevel.WARN, LogSource.GITHUB, ACTION, null, "Only the first " + MAX_PAGES
                        + " pages of repositories were fetched; the list is incomplete.", request(page), null, null);
                break;
            }
            GithubClient.Reply reply = fetch(page, token);
            if (!reply.ok()) {
                throw failed(reply, page, token);
            }
            readPage(reply, page, token, repositories);
            page = GithubClient.nextLink(reply.headers()).orElse(null);
            if (page != null && !client.isApiAddress(page)) {
                String message = "GitHub's answer pointed to another address for the next page; Dockline did not follow it.";
                throw unavailable(token, LogLevel.ERROR, null, request(page), null, message,
                        new SourceStatus(Source.GITHUB, ConnectionState.LIMITED, message));
            }
        }
        repositories.sort(NEWEST_PUSH_FIRST);
        return new Repositories(List.copyOf(repositories), fetchedAt, truncated);
    }

    private GithubClient.Reply fetch(URI page, String token) {
        try {
            return client.getPage(page, token);
        } catch (RestClientException e) {
            SourceStatus unreachable = GithubStatusProvider.unreachable(e);
            status.observed(token, unreachable);
            problemLog.report(LogLevel.WARN, LogSource.GITHUB, ACTION, null, unreachable.message(), request(page), null, e);
            throw new ApiProblem(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "GitHub is unavailable",
                    "Could not reach GitHub. Check your internet connection and try again.");
        }
    }

    private ApiProblem failed(GithubClient.Reply reply, URI page, String token) {
        int code = reply.status();
        if (code == 401) {
            status.observed(token, GithubStatusProvider.revoked());
            problemLog.report(LogLevel.ERROR, LogSource.GITHUB, ACTION, code, GithubStatusProvider.REVOKED,
                    request(page), reply.body(), null);
            return new ApiProblem(HttpStatus.CONFLICT, TOKEN_REJECTED, "GitHub rejected the token",
                    GithubStatusProvider.REVOKED);
        }
        if (GithubStatusProvider.isRateLimit(reply)) {
            SourceStatus limited = GithubStatusProvider.rateLimited();
            status.observed(token, limited);
            problemLog.report(LogLevel.WARN, LogSource.GITHUB, ACTION, code, limited.message(), request(page),
                    reply.body(), null);
            return new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, RATE_LIMITED, "GitHub rate limit reached",
                    limited.message(), retryAfter(reply).orElse(null));
        }
        SourceStatus failing = code == 403 ? GithubStatusProvider.refused(code) : GithubStatusProvider.failing(code);
        return unavailable(token, LogLevel.WARN, code, request(page), reply.body(), failing.message(), failing);
    }

    private ApiProblem unavailable(String token, LogLevel level, Integer code, String request, String response,
            String message, SourceStatus newStatus) {
        status.observed(token, newStatus);
        problemLog.report(level, LogSource.GITHUB, ACTION, code, message, request, response, null);
        return new ApiProblem(HttpStatus.BAD_GATEWAY, UNAVAILABLE, "GitHub is unavailable",
                message + " The repositories could not be loaded; try again later.");
    }

    private void readPage(GithubClient.Reply reply, URI page, String token, List<Repository> into) {
        try {
            JsonNode items = json.readTree(reply.body());
            if (!items.isArray()) {
                throw new IllegalArgumentException("not a list");
            }
            for (JsonNode item : items) {
                into.add(new Repository(
                        required(item, "name"),
                        required(item, "full_name"),
                        item.path("private").asBoolean(false) ? Visibility.PRIVATE : Visibility.PUBLIC,
                        instant(item.path("pushed_at")),
                        required(item, "html_url"),
                        item.path("fork").asBoolean(false),
                        item.path("archived").asBoolean(false)));
            }
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException e) {
            throw unavailable(token, LogLevel.WARN, reply.status(), request(page), reply.body(),
                    "GitHub's list of repositories could not be read (" + e.getMessage() + ").",
                    new SourceStatus(Source.GITHUB, ConnectionState.LIMITED, "GitHub sent an answer Dockline could not read."));
        }
    }

    private static String required(JsonNode item, String field) {
        String value = item.path(field).asString(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a repository has no " + field);
        }
        return value;
    }

    private static Instant instant(JsonNode value) {
        return value.isString() ? Instant.parse(value.asString()) : null;
    }

    /** GitHub's Retry-After, else the seconds until x-ratelimit-reset (epoch seconds); empty when neither is usable. */
    private Optional<String> retryAfter(GithubClient.Reply reply) {
        String retryAfter = reply.headers().getFirst("retry-after");
        if (retryAfter != null && retryAfter.strip().matches("\\d+")) {
            return Optional.of(retryAfter.strip());
        }
        try {
            String reset = reply.headers().getFirst("x-ratelimit-reset");
            if (reset == null) {
                return Optional.empty();
            }
            long seconds = Long.parseLong(reset.strip()) - clock.instant().getEpochSecond();
            return Optional.of(Long.toString(Math.max(1, seconds)));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String request(URI page) {
        return "GET " + page + " (with the saved token)";
    }
}
