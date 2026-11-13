package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import java.util.List;

/**
 * A fake Hostinger public API for backend tests: {@code GET /api/billing/v1/subscriptions}, the call
 * Dockline uses to check a token, answering the way Hostinger's documentation describes
 * (errors are {@code {"message": ..., "correlation_id": ...}}).
 *
 * <p>The same fakes exist for the desktop E2E tests in {@code desktop/e2e/support/hostingerStubs.ts}.
 * A later stub for the same endpoint wins, so a test can change Hostinger's answer between two calls.
 */
public final class HostingerStubs {

    public static final String SUBSCRIPTIONS_PATH = "/api/billing/v1/subscriptions";
    public static final String WEBSITES_PATH = "/api/hosting/v1/websites";

    private HostingerStubs() {}

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

    public static void websitesServerError(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH)).willReturn(error(500, """
                {"message":"Server Error","correlation_id":"c0ffee00-0000-4000-8000-000000000013"}""")));
    }

    public static void websitesConnectionReset(WireMockServer hostinger) {
        hostinger.stubFor(get(urlPathEqualTo(WEBSITES_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder error(int status, String body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body);
    }
}
