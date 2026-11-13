package io.github.delucagit.dockline.log;

import io.github.delucagit.dockline.ApiProblem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns any unexpected exception in a REST handler into an ERROR entry in the Log section (with stack
 * trace) and a masked RFC 9457 problem response. Spring's own errors (404, 405, bad parameters ...)
 * are answered as problem responses by the base class and are not logged as errors.
 */
@RestControllerAdvice
class RestErrorHandler extends ResponseEntityExceptionHandler {

    private final ProblemLog problemLog;

    RestErrorHandler(ProblemLog problemLog) {
        this.problemLog = problemLog;
    }

    /** An expected failure of a data endpoint; the endpoint has already written any Log entry it needs. */
    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ProblemDetail> apiProblem(ApiProblem problem) {
        ResponseEntity.BodyBuilder response = ResponseEntity.status(problem.status())
                .contentType(MediaType.APPLICATION_PROBLEM_JSON);
        if (problem.retryAfter() != null) {
            response.header(HttpHeaders.RETRY_AFTER, problem.retryAfter());
        }
        return response.body(problem.toProblemDetail());
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error, HttpServletRequest request) {
        String action = request.getMethod() + " " + request.getRequestURI();
        String requestText = request.getQueryString() == null ? action : action + "?" + request.getQueryString();
        LogEntry entry = problemLog.report(LogLevel.ERROR, LogSource.BACKEND, action, 500,
                "Unexpected error: " + error, requestText, null, error);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, entry.message());
        problem.setProperty("logEntryId", entry.id());
        return problem;
    }
}
