package io.github.delucagit.dockline.hostinger;

import io.github.delucagit.dockline.ApiProblem;
import io.github.delucagit.dockline.hostinger.HostingerApi.Site;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.Commit;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployOrigin;
import io.github.delucagit.dockline.hostinger.SiteDeployApi.DeployStatus;
import io.github.delucagit.dockline.log.LogLevel;
import io.github.delucagit.dockline.log.LogSource;
import io.github.delucagit.dockline.log.ProblemLog;
import io.github.delucagit.dockline.status.ConnectionState;
import io.github.delucagit.dockline.status.SourceStatus;
import io.github.delucagit.dockline.token.Source;
import io.github.delucagit.dockline.token.TokenStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The latest deploy of a website, from Hostinger's build history:
 * {@code GET .../accounts/{username}/websites/{domain}/nodejs/builds} answers
 * {@code {data: [{uuid, state, options, created_at, updated_at}], meta: {current_page, per_page, total}}}.
 * Only Node.js websites ({@code website_type} = {@value #NODEJS}) have builds; for every other website the
 * answer is "no deploy information" and Hostinger is not asked.
 *
 * <p>Hostinger does not document the order of the list. So page 1 is read with the largest page size
 * ({@link HostingerClient#BUILDS_PER_PAGE}) and the build with the newest {@code created_at} is taken; when
 * {@code meta.total} says there are more builds than that page holds, the last page is read too and the newest
 * of both is taken. A site therefore costs one request, or two with more than 100 builds, and none while the
 * answers are saved ({@link HostingerClient#BUILDS_TTL}).
 *
 * <p>Mapping: {@code completed} is SUCCEEDED, {@code failed} FAILED, {@code pending} and {@code running}
 * IN_PROGRESS, anything else UNKNOWN. The time is {@code updated_at} ({@code created_at} when missing); the
 * commit is {@code options.source_options.commit}, which is missing until the repository is cloned and for
 * uploaded archives.
 *
 * <p>No deploy information (never an error): a website that is not Node.js, one without a username or domain
 * (it cannot be asked), an empty list, and a 404 or 422 from Hostinger.
 *
 * <p>Failures become an {@link ApiProblem} with the same codes, Log entries and status updates as
 * {@link HostingerSiteList}.
 */
@Component
class HostingerBuilds implements DeploySource {

    static final String NODEJS = "nodejs";

    /** A username or domain that can go into a URL path as it is. Anything else is not asked about. */
    private static final Pattern PATH_SAFE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,252}");

    private final TokenStore tokenStore;
    private final HostingerClient client;
    private final HostingerStatusProvider status;
    private final ProblemLog problemLog;
    private final ObjectMapper json = new ObjectMapper();

    HostingerBuilds(TokenStore tokenStore, HostingerClient client, HostingerStatusProvider status,
            ProblemLog problemLog) {
        this.tokenStore = tokenStore;
        this.client = client;
        this.status = status;
        this.problemLog = problemLog;
    }

    @Override
    public Optional<Deploy> latest(Site site, Mode mode) {
        if (site.websiteType() == null || !site.websiteType().equalsIgnoreCase(NODEJS)
                || site.username() == null || site.domain() == null
                || !PATH_SAFE.matcher(site.username()).matches() || !PATH_SAFE.matcher(site.domain()).matches()) {
            return Optional.of(Deploy.none());
        }
        String token = tokenStore.read(Source.HOSTINGER).orElseThrow(() -> new ApiProblem(HttpStatus.CONFLICT,
                HostingerSiteList.NOT_CONNECTED, "Hostinger is not connected",
                "Connect to Hostinger in Settings to see the deploys of your websites."));
        try {
            HostingerClient.Reply first = get(site, 1, token, mode);
            Instant fetchedAt = first.fetchedAt();
            if (first.noBuilds()) {
                return Optional.of(none(fetchedAt));
            }
            JsonNode page = read(first, site, token);
            JsonNode newest = newest(null, page.path("data"));
            int lastPage = lastPage(page);
            if (lastPage > 1) {
                HostingerClient.Reply last = get(site, lastPage, token, mode);
                if (last.fetchedAt().isBefore(fetchedAt)) {
                    fetchedAt = last.fetchedAt();
                }
                if (!last.noBuilds()) {
                    newest = newest(newest, read(last, site, token).path("data"));
                }
            }
            return Optional.of(newest == null ? none(fetchedAt) : deploy(newest, fetchedAt));
        } catch (NotSaved e) {
            return Optional.empty();
        }
    }

    private static Deploy none(Instant fetchedAt) {
        return new Deploy(DeployStatus.NONE, null, null, DeployOrigin.NONE, fetchedAt);
    }

    /** The build with the newest {@code created_at} of {@code current} and the list; the earlier one on a tie. */
    private static JsonNode newest(JsonNode current, JsonNode builds) {
        JsonNode newest = current;
        for (JsonNode build : builds) {
            if (!build.isObject()) {
                continue;
            }
            Instant created = instant(build.path("created_at"));
            Instant newestCreated = newest == null ? null : instant(newest.path("created_at"));
            if (newest == null || (created != null && (newestCreated == null || created.isAfter(newestCreated)))) {
                newest = build;
            }
        }
        return newest;
    }

    /** The number of the last page when the builds do not fit on page 1; otherwise 1. */
    private static int lastPage(JsonNode page) {
        JsonNode meta = page.path("meta");
        int onPage = page.path("data").size();
        if (onPage == 0 || !meta.path("total").isIntegralNumber() || meta.path("total").asLong() <= onPage) {
            return 1;
        }
        long perPage = meta.path("per_page").isIntegralNumber() && meta.path("per_page").asLong() > 0
                ? meta.path("per_page").asLong() : HostingerClient.BUILDS_PER_PAGE;
        return (int) Math.min(Integer.MAX_VALUE, (meta.path("total").asLong() + perPage - 1) / perPage);
    }

    private static Deploy deploy(JsonNode build, Instant fetchedAt) {
        String state = build.path("state").asString("").toLowerCase(Locale.ROOT);
        DeployStatus deployStatus = switch (state) {
            case "completed" -> DeployStatus.SUCCEEDED;
            case "failed" -> DeployStatus.FAILED;
            case "pending", "running" -> DeployStatus.IN_PROGRESS;
            default -> DeployStatus.UNKNOWN;
        };
        Instant at = instant(build.path("updated_at"));
        if (at == null) {
            at = instant(build.path("created_at"));
        }
        JsonNode commit = build.path("options").path("source_options").path("commit");
        String hash = text(commit.path("hash"));
        return new Deploy(deployStatus, at, hash == null ? null : new Commit(hash, text(commit.path("message"))),
                DeployOrigin.HOSTINGER, fetchedAt);
    }

    private static String text(JsonNode value) {
        String text = value.asString(null);
        return text == null || text.isBlank() ? null : text;
    }

    /** Hostinger sends e.g. {@code 2024-05-29T05:49:49.067239Z}; null when missing or unreadable. */
    private static Instant instant(JsonNode value) {
        try {
            return value.isString() ? OffsetDateTime.parse(value.asString()).toInstant() : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Hostinger's answer for one page: a successful one, or its 404/422 "no builds here". */
    private HostingerClient.Reply get(Site site, int page, String token, Mode mode) {
        String path = HostingerClient.nodejsBuildsPath(site.username(), site.domain());
        if (mode == Mode.SAVED_ONLY) {
            return client.savedNodejsBuilds(site.username(), site.domain(), page, token).orElseThrow(NotSaved::new);
        }
        HostingerClient.Reply reply;
        try {
            reply = client.getNodejsBuilds(site.username(), site.domain(), page, token, mode == Mode.REFRESH);
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
                    "Could not reach Hostinger. Check your internet connection and try again.");
        }
        if (reply.ok() || reply.noBuilds()) {
            return reply;
        }
        int code = reply.status();
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

    /** The page as JSON; it must be an object with a {@code data} list. */
    private JsonNode read(HostingerClient.Reply reply, Site site, String token) {
        String path = HostingerClient.nodejsBuildsPath(site.username(), site.domain());
        String why;
        try {
            JsonNode page = json.readTree(reply.body());
            if (page.path("data").isArray()) {
                return page;
            }
            why = "no data list";
        } catch (JacksonException e) {
            why = e.getMessage();
        }
        throw unavailable(token, reply.status(), path, reply.body(),
                "Hostinger's builds of a website could not be read (" + why + ").",
                new SourceStatus(Source.HOSTINGER, ConnectionState.LIMITED,
                        "Hostinger sent an answer Dockline could not read."));
    }

    private ApiProblem unavailable(String token, Integer code, String path, String response, String message,
            SourceStatus newStatus) {
        status.observed(token, newStatus);
        problemLog.report(LogLevel.WARN, LogSource.HOSTINGER, "GET " + path, code, message, request(path), response,
                null);
        return new ApiProblem(HttpStatus.BAD_GATEWAY, HostingerSiteList.UNAVAILABLE, "Hostinger is unavailable",
                message + " The site's latest deploy could not be loaded; try again later.");
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
