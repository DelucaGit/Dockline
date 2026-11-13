package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.List;

/**
 * A fake Hostinger public API for backend tests: {@code GET /api/billing/v1/subscriptions}, the call
 * Dockline uses to check a token, answering the way Hostinger's documentation describes
 * (errors are {@code {"message": ..., "correlation_id": ...}}).
 *
 * <p>A website's Git connection has fakes for each case: connected by auto-deployment
 * ({@link #gitAutoDeployment}), connected through a repository cloned in hPanel's Git section
 * ({@link #gitRepositories}), and not connected ({@link #gitNotConnected}). The shapes follow Hostinger's
 * OpenAPI document (github.com/hostinger/api, openapi.json).
 *
 * <p>The same fakes exist for the desktop E2E tests in {@code desktop/e2e/support/hostingerStubs.ts}.
 * A later stub for the same endpoint wins, so a test can change Hostinger's answer between two calls.
 */
public final class HostingerStubs {

    public static final String SUBSCRIPTIONS_PATH = "/api/billing/v1/subscriptions";
    public static final String WEBSITES_PATH = "/api/hosting/v1/websites";
    public static final String DOMAINS_PATH = "/api/domains/v1/portfolio";

    public static final String GIT_INSTALLATIONS_PATH = "/api/hosting/v1/git/installations";
    /** The installation every {@link #gitAutoDeployment} fake goes through. */
    public static final String GIT_INSTALLATION_UUID = "018f5e2a-1234-7890-abcd-1234567890ab";

    private HostingerStubs() {}

    public static String gitRepositoriesPath(String username, String domain) {
        return "/api/hosting/v1/accounts/" + username + "/websites/" + domain + "/git/repositories";
    }

    public static String gitAutoDeploymentPath(String username, String domain) {
        return "/api/hosting/v1/accounts/" + username + "/websites/" + domain + "/git/auto-deployments/settings";
    }

    /**
     * The website's hPanel Git section lists these clone addresses (the first one in the document root, the
     * others in {@code dir-1}, {@code dir-2} ...), e.g. {@code git@github.com:octocat/my-site.git} or
     * {@code https://***@github.com/octocat/my-site.git}. None means an empty list.
     */
    public static void gitRepositories(WireMockServer hostinger, String username, String domain, String... repositoryUrls) {
        List<String> items = new java.util.ArrayList<>();
        for (int i = 0; i < repositoryUrls.length; i++) {
            items.add("""
                    {"repository_url":"%s","branch":"main","directory":"%s","webhook":{\
                    "url":"https://webhooks.hostinger.com/deploy/6f1ed002ab5595859014ebf0951522d%d","provider":null,\
                    "setup_url":null,"tutorial_url":null}}""".formatted(repositoryUrls[i], i == 0 ? "" : "dir-" + i, i));
        }
        hostinger.stubFor(get(urlPathEqualTo(gitRepositoriesPath(username, domain)))
                .willReturn(okJson("[" + String.join(",", items) + "]")));
    }

    /**
     * The website auto-deploys {@code owner/repository} (branch main, document root) through the installation
     * {@link #GIT_INSTALLATION_UUID}; add {@link #gitInstallations} to say which provider that is.
     */
    public static void gitAutoDeployment(WireMockServer hostinger, String username, String domain, String owner,
            String repository) {
        hostinger.stubFor(get(urlPathEqualTo(gitAutoDeploymentPath(username, domain))).willReturn(okJson("""
                {"installation_uuid":"%s","is_enabled":true,"owner":"%s","repository":"%s","branch":"main",\
                "directory":""}""".formatted(GIT_INSTALLATION_UUID, owner, repository))));
    }

    /** The website has no auto-deployment: every field is null, as Hostinger documents. */
    public static void gitNoAutoDeployment(WireMockServer hostinger, String username, String domain) {
        hostinger.stubFor(get(urlPathEqualTo(gitAutoDeploymentPath(username, domain))).willReturn(okJson("""
                {"installation_uuid":null,"is_enabled":null,"owner":null,"repository":null,"branch":null,\
                "directory":null}""")));
    }

    /** A website that is not Git-connected at all: no auto-deployment and an empty Git section. */
    public static void gitNotConnected(WireMockServer hostinger, String username, String domain) {
        gitNoAutoDeployment(hostinger, username, domain);
        gitRepositories(hostinger, username, domain);
    }

    /** A website of a kind that has no Git at all: Hostinger answers 404 on both Git paths. */
    public static void gitNotAvailable(WireMockServer hostinger, String username, String domain) {
        hostinger.stubFor(get(urlPathEqualTo(gitAutoDeploymentPath(username, domain))).willReturn(error(404,
                "{\"message\":\"Not Found\",\"correlation_id\":\"c0ffee00-0000-4000-8000-000000000031\"}")));
        hostinger.stubFor(get(urlPathEqualTo(gitRepositoriesPath(username, domain))).willReturn(error(404,
                "{\"message\":\"Not Found\",\"correlation_id\":\"c0ffee00-0000-4000-8000-000000000031\"}")));
    }

    /** The customer's one Git installation ({@link #GIT_INSTALLATION_UUID}); provider github, gitlab or bitbucket. */
    public static void gitInstallations(WireMockServer hostinger, String provider) {
        hostinger.stubFor(get(urlPathEqualTo(GIT_INSTALLATIONS_PATH)).willReturn(okJson("""
                [{"uuid":"%s","provider":"%s","account_login":"octocat","account_type":"User",\
                "account_avatar_url":null,"status":"active","installed_at":"2026-01-15T10:30:00Z",\
                "created_at":"2026-01-15T10:00:00Z","has_oauth":true}]""".formatted(GIT_INSTALLATION_UUID, provider))));
    }

    /** Both Git endpoints of the website answer 500. */
    public static void gitServerError(WireMockServer hostinger, String username, String domain) {
        for (String path : List.of(gitAutoDeploymentPath(username, domain), gitRepositoriesPath(username, domain))) {
            hostinger.stubFor(get(urlPathEqualTo(path)).willReturn(error(500, """
                    {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000033"}""")));
        }
    }

    /** Both Git endpoints of the website answer 401; {@code echo} is put into the body to check that it gets masked. */
    public static void gitUnauthorized(WireMockServer hostinger, String username, String domain, String echo) {
        for (String path : List.of(gitAutoDeploymentPath(username, domain), gitRepositoriesPath(username, domain))) {
            hostinger.stubFor(get(urlPathEqualTo(path)).willReturn(error(401, """
                    {"message":"Unauthenticated.","correlation_id":"c0ffee00-0000-4000-8000-000000000031","debug":"%s"}"""
                    .formatted(echo))));
        }
    }

    /** Both Git endpoints of the website answer 429 with Retry-After. */
    public static void gitRateLimited(WireMockServer hostinger, String username, String domain, int retryAfterSeconds) {
        for (String path : List.of(gitAutoDeploymentPath(username, domain), gitRepositoriesPath(username, domain))) {
            hostinger.stubFor(get(urlPathEqualTo(path)).willReturn(error(429, """
                    {"message":"Too Many Attempts.","correlation_id":"c0ffee00-0000-4000-8000-000000000032"}""")
                    .withHeader("Retry-After", Integer.toString(retryAfterSeconds))));
        }
    }

    /** A WireMock server on a free localhost port, started. Stop it when done. */
    public static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort().bindAddress("127.0.0.1"));
        server.start();
        return server;
    }

    /** The token works; the account has one subscription. */
    public static void subscriptionsOk(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(SUBSCRIPTIONS_PATH)).willReturn(okJson("""
                [{"id":"Azz353Uhl1xC54pR0","name":"Premium Web Hosting","status":"active","billing_period":12,\
                "billing_period_unit":"month","currency_code":"USD","total_price":1188,"renewal_price":1428,\
                "is_auto_renewed":true,"created_at":"2025-02-27T11:54:22Z","expires_at":"2026-02-27T11:54:22Z"}]""")));
    }

    /** The token is missing, expired or revoked. {@code echo} is put into the body to check that it gets masked. */
    public static void unauthorized(WireMockServer hostinger, String echo) {
        hostinger.stubFor(get(urlPathEqualTo(SUBSCRIPTIONS_PATH)).willReturn(aResponse().withStatus(401)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":"Unauthenticated.","correlation_id":"c0ffee00-0000-4000-8000-000000000001",\
                        "debug":"%s"}""".formatted(echo))));
    }

    public static void rateLimited(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(SUBSCRIPTIONS_PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", "30")
                .withBody("""
                        {"message":"Too Many Attempts.","correlation_id":"c0ffee00-0000-4000-8000-000000000002"}""")));
    }

    public static void serverError(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(SUBSCRIPTIONS_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000003"}""")));
    }

    /** No answer at all: the connection is reset. */
    public static void connectionReset(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(SUBSCRIPTIONS_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /**
     * One website as {@code GET /api/hosting/v1/websites} returns it (CloudLinux fields filled in).
     * {@code createdAt} is in Hostinger's format, e.g. {@code 2024-01-15T10:30:00+00:00}.
     */
    public static String site(String domain, String username, long orderId, boolean enabled, String createdAt) {
        return """
                {"domain":"%s","vhost_type":"main","is_enabled":%s,"username":"%s","client_id":67890,                "order_id":%d,"created_at":"%s","root_directory":"/home/%s/domains/%s/public_html",                "parent_domain":null,"website_type":"wordpress","horizons_uuid":null}"""
                .formatted(domain, enabled, username, orderId, createdAt, username, domain, domain);
    }

    /**
     * The website list, split into the given pages (page 1 first), answering {@code ?page=N} the way
     * Hostinger does: {@code {"data": [...], "meta": {"current_page", "per_page", "total"}}}. {@code per_page}
     * is the size of the largest page, so a short last page ends the list. Any page past the end is empty.
     */
    public static void websites(WireMockServer hostinger, List<List<String>> pages) {
        int perPage = Math.max(1, pages.stream().mapToInt(List::size).max().orElse(0));
        int total = pages.stream().mapToInt(List::size).sum();
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).willReturn(okJson(websitesPage(List.of(), 0, perPage, total))));
        for (int i = 0; i < pages.size(); i++) {
            hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).withQueryParam("page", equalTo(Integer.toString(i + 1)))
                    .willReturn(okJson(websitesPage(pages.get(i), i + 1, perPage, total))));
        }
    }

    private static String websitesPage(List<String> sites, int page, int perPage, int total) {
        return """
                {"data":[%s],"meta":{"current_page":%d,"per_page":%d,"total":%d}}"""
                .formatted(String.join(",", sites), page, perPage, total);
    }

    /** The website list answers 401; {@code echo} is put into the body to check that it gets masked. */
    public static void websitesUnauthorized(WireMockServer hostinger, String echo) {
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).willReturn(error(401, """
                {"message":"Unauthenticated.","correlation_id":"c0ffee00-0000-4000-8000-000000000011","debug":"%s"}"""
                .formatted(echo))));
    }

    public static void websitesRateLimited(WireMockServer hostinger, int retryAfterSeconds) {
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).willReturn(error(429, """
                {"message":"Too Many Attempts.","correlation_id":"c0ffee00-0000-4000-8000-000000000012"}""")
                .withHeader("Retry-After", Integer.toString(retryAfterSeconds))));
    }

    /**
     * The website list answers 429 once (with {@code retryAfter} as the Retry-After header, or none when null),
     * then the earlier/other website stubs answer again: Hostinger saying "slow down" and then recovering.
     * Uses a WireMock scenario; {@code resetAll()} starts it over.
     */
    public static void websitesRateLimitedOnce(WireMockServer hostinger, String retryAfter) {
        var answer = error(429, """
                {"message":"Too Many Attempts.","correlation_id":"c0ffee00-0000-4000-8000-000000000014"}""");
        if (retryAfter != null) {
            answer = answer.withHeader("Retry-After", retryAfter);
        }
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).atPriority(1)
                .inScenario("websites-rate-limited-once").whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered").willReturn(answer));
    }

    public static void websitesServerError(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).willReturn(error(500, """
                {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000013"}""")));
    }

    public static void websitesConnectionReset(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /**
     * One domain as {@code GET /api/domains/v1/portfolio} returns it. {@code domain} and {@code expiresAt} may be
     * null (a free domain not claimed yet); timestamps are in Hostinger's format, e.g. {@code 2026-02-27T11:54:22Z}.
     */
    public static String domain(long id, String domain, String status, String expiresAt) {
        return """
                {"id":%d,"domain":%s,"type":"domain","status":"%s","created_at":"2025-02-27T11:54:22Z","expires_at":%s}"""
                .formatted(id, domain == null ? "null" : "\"" + domain + "\"", status,
                        expiresAt == null ? "null" : "\"" + expiresAt + "\"");
    }

    /** The domain portfolio: a plain JSON array, no paging. */
    public static void domains(WireMockServer hostinger, List<String> domains) {
        hostinger.stubFor(get(urlPathEqualTo(DOMAINS_PATH)).willReturn(okJson("[" + String.join(",", domains) + "]")));
    }

    /** {@code domain} as it appears in the address, i.e. already URL-encoded if it needs to be. */
    public static String domainRenewalPath(String domain) {
        return DOMAINS_PATH + "/" + domain + "/renewal";
    }

    /** The domain's renewal information ({@code GET /api/domains/v1/portfolio/{domain}/renewal}) with this expiry date. */
    public static void domainRenewal(WireMockServer hostinger, String domain, String expiresAt) {
        hostinger.stubFor(get(urlPathEqualTo(domainRenewalPath(domain))).willReturn(okJson("""
                {"domain":"%s","status":"active","expires_at":"%s","is_grace_period":false,\
                "grace_period_ends_at":null,"is_redemption_period":false,"redemption_period_ends_at":null,\
                "is_renewable":true}""".formatted(domain, expiresAt))));
    }

    public static void domainRenewalServerError(WireMockServer hostinger, String domain) {
        hostinger.stubFor(get(urlPathEqualTo(domainRenewalPath(domain))).willReturn(error(500, """
                {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000024"}""")));
    }

    /** The domain portfolio answers 401; {@code echo} is put into the body to check that it gets masked. */
    public static void domainsUnauthorized(WireMockServer hostinger, String echo) {
        hostinger.stubFor(get(urlPathEqualTo(DOMAINS_PATH)).willReturn(error(401, """
                {"message":"Unauthenticated.","correlation_id":"c0ffee00-0000-4000-8000-000000000021","debug":"%s"}"""
                .formatted(echo))));
    }

    public static void domainsRateLimited(WireMockServer hostinger, int retryAfterSeconds) {
        hostinger.stubFor(get(urlPathEqualTo(DOMAINS_PATH)).willReturn(error(429, """
                {"message":"Too Many Attempts.","correlation_id":"c0ffee00-0000-4000-8000-000000000022"}""")
                .withHeader("Retry-After", Integer.toString(retryAfterSeconds))));
    }

    public static void domainsServerError(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(DOMAINS_PATH)).willReturn(error(500, """
                {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000023"}""")));
    }

    public static void domainsConnectionReset(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(DOMAINS_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder error(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
