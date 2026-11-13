package io.github.delucagit.dockline.github;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * A fake GitHub for backend tests: the device-flow endpoints (github.com/login/...) and {@code GET /user}
 * (api.github.com), answering the way GitHub's documentation describes. GitHub answers device-flow
 * errors with HTTP 200 and an {@code error} field, so the fakes do too.
 *
 * <p>The same fakes exist for the desktop E2E tests in {@code desktop/e2e/support/githubStubs.ts}.
 * A later stub for the same endpoint wins, so a test can change GitHub's answer between two polls.
 */
public final class GithubStubs {

    public static final String DEVICE_CODE_PATH = "/login/device/code";
    public static final String TOKEN_PATH = "/login/oauth/access_token";
    public static final String USER_PATH = "/user";

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
}
