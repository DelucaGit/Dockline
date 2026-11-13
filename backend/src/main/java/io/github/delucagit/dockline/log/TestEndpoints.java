package io.github.delucagit.dockline.log;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints that make the backend report problems on demand, so end-to-end tests can check
 * the Log section and token masking. They only exist when {@code dockline.test-endpoints=true}
 * (environment variable {@code DOCKLINE_TEST_ENDPOINTS=true}); off by default and hidden from the API description.
 */
@Hidden
@RestController
@RequestMapping("/api/test")
@ConditionalOnProperty(name = "dockline.test-endpoints", havingValue = "true")
class TestEndpoints {

    /** Looks like a real GitHub token, so tests can prove it never comes out unmasked. Not a real token. */
    static final String FAKE_TOKEN = "ghp_" + "0123456789abcdef" + "TESTtokenNOTreal1234";

    private final ProblemLog problemLog;

    TestEndpoints(ProblemLog problemLog) {
        this.problemLog = problemLog;
    }

    /** Reports a problem; every text contains {@link #FAKE_TOKEN}. Returns the stored (masked) entry. */
    @PostMapping("/report-error")
    LogEntry reportError(
            @RequestParam(defaultValue = "ERROR") LogLevel level,
            @RequestParam(defaultValue = "GITHUB") LogSource source,
            @RequestParam(defaultValue = "GET /user") String action,
            @RequestParam(defaultValue = "401") Integer statusCode,
            @RequestParam(defaultValue = "Test error: the token was rejected") String message) {
        return problemLog.report(level, source, action, statusCode, message + " (token " + FAKE_TOKEN + ")",
                action + "\nAuthorization: Bearer " + FAKE_TOKEN,
                "{\"message\":\"Bad credentials\",\"token\":\"" + FAKE_TOKEN + "\"}",
                new IllegalStateException("Simulated failure using " + FAKE_TOKEN));
    }

    /** Throws an unexpected exception, to exercise the uncaught-exception path. */
    @PostMapping("/fail")
    void fail() {
        throw new IllegalStateException("Simulated unexpected failure with token " + FAKE_TOKEN);
    }
}
