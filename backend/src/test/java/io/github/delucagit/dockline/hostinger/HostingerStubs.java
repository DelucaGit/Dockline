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
 * <p>A Node.js website's builds (deploys) have fakes too: {@link #buildSucceeded}, {@link #buildFailed},
 * {@link #buildInProgress}, {@link #buildsEmpty}, {@link #buildsNotFound} and {@link #buildsServerError},
 * built from the same document's examples.
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

    /** The same with Hostinger's {@code website_type}: wordpress, builder, horizons, nodejs or other. */
    public static String site(String domain, String username, long orderId, boolean enabled, String createdAt,
            String websiteType) {
        return site(domain, username, orderId, enabled, createdAt)
                .replace("\"website_type\":\"wordpress\"", "\"website_type\":\"" + websiteType + "\"");
    }

    public static String nodejsBuildsPath(String username, String domain) {
        return "/api/hosting/v1/accounts/" + username + "/websites/" + domain + "/nodejs/builds";
    }

    /**
     * One build as {@code GET .../nodejs/builds} returns it, built from a Git repository (octocat/my-site, branch
     * main). {@code state} is pending, running, completed or failed. {@code commitHash} null means Hostinger has
     * not cloned the repository yet: {@code commit} is null. Timestamps are in Hostinger's format, e.g.
     * {@code 2024-05-29T05:49:49.067239Z}.
     */
    public static String build(String uuid, String state, String createdAt, String updatedAt, String commitHash,
            String commitMessage) {
        String commit = commitHash == null ? "null" : """
                {"hash":"%s","message":"%s","author":{"name":"Jane Doe"}}""".formatted(commitHash, commitMessage);
        return """
                {"uuid":"%s","state":"%s","options":{"source_type":"git","source_options":{"owner":"octocat",\
                "repository":"my-site","branch":"main","commit":%s}},"created_at":"%s","updated_at":"%s"}"""
                .formatted(uuid, state, commit, createdAt, updatedAt);
    }

    /** A build from an uploaded archive: no repository and no commit ({@code source_options} is null). */
    public static String archiveBuild(String uuid, String state, String createdAt, String updatedAt) {
        return """
                {"uuid":"%s","state":"%s","options":{"source_type":"archive","source_options":null},\
                "created_at":"%s","updated_at":"%s"}""".formatted(uuid, state, createdAt, updatedAt);
    }

    /** The website's builds, all on one page, in the order given (Hostinger does not document the order). */
    public static void builds(WireMockServer hostinger, String username, String domain, String... builds) {
        hostinger.stubFor(get(urlPathEqualTo(nodejsBuildsPath(username, domain)))
                .willReturn(okJson(buildsBody(List.of(builds), 1, 100, builds.length))));
    }

    /**
     * One page of the website's builds, answering only {@code ?page=N}; {@code total} is the number of builds on
     * all pages together, {@code perPage} the page size Hostinger reports.
     */
    public static void buildsPage(WireMockServer hostinger, String username, String domain, int page, int perPage,
            int total, String... builds) {
        hostinger.stubFor(get(urlPathEqualTo(nodejsBuildsPath(username, domain)))
                .withQueryParam("page", equalTo(Integer.toString(page)))
                .willReturn(okJson(buildsBody(List.of(builds), page, perPage, total))));
    }

    private static String buildsBody(List<String> builds, int page, int perPage, int total) {
        return """
                {"data":[%s],"meta":{"current_page":%d,"per_page":%d,"total":%d}}"""
                .formatted(String.join(",", builds), page, perPage, total);
    }

    /** The website's latest (and only) build succeeded, with this commit. */
    public static void buildSucceeded(WireMockServer hostinger, String username, String domain, String commitHash,
            String commitMessage) {
        builds(hostinger, username, domain, build("69f07fe2-197a-4fb3-9dae-606f965ad13d", "completed",
                "2024-05-29T05:49:49.067239Z", "2024-05-29T05:51:02.000000Z", commitHash, commitMessage));
    }

    /** The website's latest (and only) build failed, with this commit. */
    public static void buildFailed(WireMockServer hostinger, String username, String domain, String commitHash,
            String commitMessage) {
        builds(hostinger, username, domain, build("7a0181f3-2a8b-4c0c-8ebf-717fa76be24e", "failed",
                "2024-05-29T06:10:00.000000Z", "2024-05-29T06:10:41.000000Z", commitHash, commitMessage));
    }

    /**
     * The website's latest (and only) build is not finished: {@code state} pending (no commit known yet) or
     * running (the commit is known).
     */
    public static void buildInProgress(WireMockServer hostinger, String username, String domain, String state) {
        builds(hostinger, username, domain, build("8b1292a4-3b9c-4d1d-9fc0-828ab87cf35f", state,
                "2024-05-29T07:00:00.000000Z", "2024-05-29T07:00:05.000000Z",
                state.equals("pending") ? null : "b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1", "Add contact page"));
    }

    /** A Node.js website that was never built: an empty list. */
    public static void buildsEmpty(WireMockServer hostinger, String username, String domain) {
        builds(hostinger, username, domain);
    }

    /** Hostinger knows no builds for the website (e.g. it is not a Node.js website): 404. */
    public static void buildsNotFound(WireMockServer hostinger, String username, String domain) {
        hostinger.stubFor(get(urlPathEqualTo(nodejsBuildsPath(username, domain))).willReturn(error(404,
                "{\"message\":\"Not Found\",\"correlation_id\":\"c0ffee00-0000-4000-8000-000000000041\"}")));
    }

    public static void buildsServerError(WireMockServer hostinger, String username, String domain) {
        hostinger.stubFor(get(urlPathEqualTo(nodejsBuildsPath(username, domain))).willReturn(error(500, """
                {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000043"}""")));
    }

    /** The builds endpoint answers 401; {@code echo} is put into the body to check that it gets masked. */
    public static void buildsUnauthorized(WireMockServer hostinger, String username, String domain, String echo) {
        hostinger.stubFor(get(urlPathEqualTo(nodejsBuildsPath(username, domain))).willReturn(error(401, """
                {"message":"Unauthenticated.","correlation_id":"c0ffee00-0000-4000-8000-000000000044","debug":"%s"}"""
                .formatted(echo))));
    }

    public static void buildsRateLimited(WireMockServer hostinger, String username, String domain,
            int retryAfterSeconds) {
        hostinger.stubFor(get(urlPathEqualTo(nodejsBuildsPath(username, domain))).willReturn(error(429, """
                {"message":"Too Many Attempts.","correlation_id":"c0ffee00-0000-4000-8000-000000000042"}""")
                .withHeader("Retry-After", Integer.toString(retryAfterSeconds))));
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
        return domain(id, domain, "domain", status, expiresAt);
    }

    /** The same with Hostinger's {@code type}: domain, domain_transfer, free_domain or free_domain_transfer. */
    public static String domain(long id, String domain, String type, String status, String expiresAt) {
        return """
                {"id":%d,"domain":%s,"type":"%s","status":"%s","created_at":"2025-02-27T11:54:22Z","expires_at":%s}"""
                .formatted(id, domain == null ? "null" : "\"" + domain + "\"", type, status,
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
