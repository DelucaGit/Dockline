package io.github.delucagit.dockline;

import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * Thrown by a data endpoint when it cannot answer; turned into an {@code application/problem+json} response
 * with a stable machine-readable {@code code} property (e.g. {@code GITHUB_NOT_CONNECTED}) that the UI can
 * switch on, a readable {@code detail} for the user and, for rate limits, a {@code Retry-After} header
 * plus the same number of seconds as a {@code retryAfterSeconds} property.
 * The detail is shown to the user as-is, so it must never contain a token.
 */
public class ApiProblem extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final String retryAfter;

    public ApiProblem(HttpStatus status, String code, String title, String detail, String retryAfter) {
        super(detail);
        this.status = Objects.requireNonNull(status);
        this.code = Objects.requireNonNull(code);
        this.title = title;
        this.retryAfter = retryAfter;
    }

    public ApiProblem(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, null);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** Seconds to wait before trying again, for the {@code Retry-After} header; null when unknown. */
    public String retryAfter() {
        return retryAfter;
    }

    public ProblemDetail toProblemDetail() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, getMessage());
        problem.setTitle(title);
        problem.setProperty("code", code);
        if (retryAfter != null && retryAfter.matches("\\d{1,9}")) {
            // The same wait as the Retry-After header, as a number the UI can count down from.
            problem.setProperty("retryAfterSeconds", Long.parseLong(retryAfter));
        }
        return problem;
    }
}
