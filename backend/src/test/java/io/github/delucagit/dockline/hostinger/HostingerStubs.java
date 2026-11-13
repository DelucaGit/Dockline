package io.github.delucagit.dockline.hostinger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;

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
}
