package io.github.delucagit.dockline.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.List;

/**
 * A fake GitHub for backend tests: the device-flow endpoints (github.com/login/...) and {@code GET /user}
 * (api.github.com), answering the way GitHub's documentation describes. GitHub answers device-flow
 * errors with HTTP 200 and an {@code error} field, so the fakes do too.
 *
 * <p>The same fakes exist for the desktop E2E tests in {@code desktop/e2e/support/githubStubs.ts}.
 * For {@code GET /user/repos}, {@link #repos} serves several pages linked with absolute {@code Link} headers,
 * as GitHub does; build the items with {@link #repo}.
 *
 * For {@code GET /repos/{owner}/{repo}} (is a linked repository still there?): {@link #repository} (found),
 * {@link #repositoryNotFound} (404) and {@link #repositoryError} (any other status).
 *
 * <p>A later stub for the same endpoint wins, so a test can change GitHub's answer between two polls.
 */
public final class GithubStubs {

    public static final String DEVICE_CODE_PATH = "/login/device/code";
    public static final String TOKEN_PATH = "/login/oauth/access_token";
    public static final String USER_PATH = "/user";
    public static final String REPOS_PATH = "/user/repos";

    private GithubStubs() {}

    /** A WireMock server on a free localhost port, started. Stop it when done. */
    public static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        return server;
    }

    public static void deviceCode(WireMockServer github, String deviceCode, String userCode, int expiresIn, int interval) {
        github.stubFor(post(urlPathEqualTo(DEVICE_CODE_PATH)).willReturn(okJson("""
                {"device_code":"%s","user_code":"%s","verification_uri":"https://github.com/login/device",\
                "expires_in":%d,"interval":%d}""".formatted(deviceCode, userCode, expiresIn, interval))));
    }

    /** Any device-flow error answer, e.g. "authorization_pending". */
    public static void tokenError(WireMockServer github, String error) {
        github.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("""
                {"error":"%s","error_description":"fake %s","error_uri":"https://docs.github.com"}""".formatted(error, error))));
    }

    public static void tokenPending(WireMockServer github) {
        tokenError(github, "authorization_pending");
    }

    /** "slow_down", with the new interval GitHub tells the client to use. */
    public static void tokenSlowDown(WireMockServer github, int newInterval) {
        github.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("""
                {"error":"slow_down","error_description":"Too many requests","interval":%d}""".formatted(newInterval))));
    }

    public static void tokenDenied(WireMockServer github) {
        tokenError(github, "access_denied");
    }

    public static void tokenExpired(WireMockServer github) {
        tokenError(github, "expired_token");
    }

    public static void tokenSuccess(WireMockServer github, String accessToken) {
        github.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("""
                {"access_token":"%s","token_type":"bearer","scope":"read:user,repo"}""".formatted(accessToken))));
    }

    public static void user(WireMockServer github, String login) {
        github.stubFor(get(urlPathEqualTo(USER_PATH)).willReturn(okJson("""
                {"login":"%s","id":1,"type":"User"}""".formatted(login))));
    }

    /** The token was revoked or has expired. */
    public static void userUnauthorized(WireMockServer github) {
        github.stubFor(get(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":"Bad credentials","documentation_url":"https://docs.github.com/rest","status":"401"}""")));
    }

    /** The primary rate limit is used up (403 with x-ratelimit-remaining: 0). */
    public static void userRateLimited(WireMockServer github) {
        github.stubFor(get(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withHeader("x-ratelimit-limit", "5000")
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", "1767272400")
                .withBody("""
                        {"message":"API rate limit exceeded","documentation_url":"https://docs.github.com/rest"}""")));
    }

    /** One item of GitHub's repository list (only the fields Dockline reads, plus a few). {@code pushedAt} may be null. */
    public static String repo(String owner, String name, boolean isPrivate, String pushedAt) {
        return """
                {"id":%d,"name":"%s","full_name":"%s/%s","private":%s,"html_url":"https://github.com/%s/%s",\
                "description":null,"fork":false,"archived":false,"visibility":"%s","pushed_at":%s,\
                "owner":{"login":"%s"}}""".formatted(Math.abs((owner + name).hashCode()), name, owner, name, isPrivate,
                owner, name, isPrivate ? "private" : "public", pushedAt == null ? "null" : "\"" + pushedAt + "\"", owner);
    }

    /**
     * {@code GET /user/repos} answering with these pages (each a list of {@link #repo} items). Every page but the
     * last has a {@code Link} header with absolute {@code next} and {@code last} addresses on this server.
     */
    public static void repos(WireMockServer github, List<List<String>> pages) {
        for (int i = 0; i < pages.size(); i++) {
            int page = i + 1;
            ResponseDefinitionBuilder response = okJson("[" + String.join(",", pages.get(i)) + "]");
            if (page < pages.size()) {
                response = response.withHeader("Link", "<%s>; rel=\"next\", <%s>; rel=\"last\"".formatted(
                        reposPageUrl(github, page + 1), reposPageUrl(github, pages.size())));
            }
            github.stubFor(get(urlPathEqualTo(REPOS_PATH))
                    .withQueryParam("page", page == 1 ? absent() : equalTo(Integer.toString(page)))
                    .willReturn(response));
        }
    }

    /** The address GitHub puts into a Link header for that page. */
    public static String reposPageUrl(WireMockServer github, int page) {
        return github.baseUrl() + REPOS_PATH + "?visibility=all&affiliation=owner%2Ccollaborator%2Corganization_member"
                + "&sort=pushed&direction=desc&per_page=100&page=" + page;
    }

    /** One page of repositories whose next link points at another host (a token must never be sent there). */
    public static void reposWithForeignNextLink(WireMockServer github, String foreignUrl, List<String> items) {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(okJson("[" + String.join(",", items) + "]")
                .withHeader("Link", "<" + foreignUrl + ">; rel=\"next\"")));
    }

    public static String repositoryPath(String owner, String name) {
        return "/repos/" + owner + "/" + name;
    }

    /** {@code GET /repos/{owner}/{name}} finds the repository. */
    public static void repository(WireMockServer github, String owner, String name) {
        github.stubFor(get(urlPathEqualTo(repositoryPath(owner, name)))
                .willReturn(okJson(repo(owner, name, false, "2026-01-01T00:00:00Z"))));
    }

    /** {@code GET /repos/{owner}/{name}} answers 404: deleted, or private and hidden from the user. */
    public static void repositoryNotFound(WireMockServer github, String owner, String name) {
        repositoryError(github, owner, name, 404, "Not Found");
    }

    /** {@code GET /repos/{owner}/{name}} answers with this status, e.g. 403, 401 or 503. */
    public static void repositoryError(WireMockServer github, String owner, String name, int status, String message) {
        github.stubFor(get(urlPathEqualTo(repositoryPath(owner, name))).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":"%s","documentation_url":"https://docs.github.com/rest","status":"%d"}"""
                        .formatted(message, status))));
    }

    /** The token was revoked or has expired. */
    public static void reposUnauthorized(WireMockServer github) {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":"Bad credentials","documentation_url":"https://docs.github.com/rest","status":"401"}""")));
    }

    /** The primary rate limit is used up: 403, x-ratelimit-remaining 0, resets at {@code resetEpochSeconds}. */
    public static void reposRateLimited(WireMockServer github, long resetEpochSeconds) {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withHeader("x-ratelimit-limit", "5000")
                .withHeader("x-ratelimit-remaining", "0")
                .withHeader("x-ratelimit-reset", Long.toString(resetEpochSeconds))
                .withBody("""
                        {"message":"API rate limit exceeded","documentation_url":"https://docs.github.com/rest"}""")));
    }

    /** A secondary rate limit: 429 with Retry-After. */
    public static void reposTooManyRequests(WireMockServer github, int retryAfterSeconds) {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", Integer.toString(retryAfterSeconds))
                .withBody("""
                        {"message":"You have exceeded a secondary rate limit.","documentation_url":"https://docs.github.com/rest"}""")));
    }

    /** GitHub itself fails. */
    public static void reposServerError(WireMockServer github) {
        github.stubFor(get(urlPathEqualTo(REPOS_PATH)).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":"Service Unavailable"}""")));
    }
}
