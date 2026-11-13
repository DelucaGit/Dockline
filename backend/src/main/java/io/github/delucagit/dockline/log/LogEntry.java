package io.github.delucagit.dockline.log;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * One warning or error, as shown in the Log section. Fields without a value are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A warning or error reported by the backend. Tokens are always masked.")
public record LogEntry(
        @Schema(requiredMode = REQUIRED, description = "Unique within one backend run; newer entries have higher ids") long id,
        @Schema(requiredMode = REQUIRED, description = "When it happened (ISO-8601, UTC)") Instant time,
        @Schema(requiredMode = REQUIRED) LogLevel level,
        @Schema(requiredMode = REQUIRED) LogSource source,
        @Schema(requiredMode = REQUIRED, description = "What the backend was doing, e.g. \"GET /user\"") String action,
        @Schema(description = "HTTP status code involved, if any") Integer statusCode,
        @Schema(requiredMode = REQUIRED) String message,
        @Schema(requiredMode = REQUIRED) Context context) {

    /** Extra details for "Copy for AI". */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(name = "LogEntryContext")
    public record Context(
            @Schema(requiredMode = REQUIRED, description = "Backend version") String appVersion,
            @Schema(description = "The request that failed (method, URL, relevant headers/body)") String request,
            @Schema(description = "The response that came back") String response,
            @Schema(description = "Java stack trace") String stackTrace) {}

    /** A copy with every text field run through {@link SecretMasker}. */
    LogEntry masked() {
        return new LogEntry(id, time, level, source, SecretMasker.mask(action), statusCode, SecretMasker.mask(message),
                new Context(context.appVersion(), SecretMasker.mask(context.request()),
                        SecretMasker.mask(context.response()), SecretMasker.mask(context.stackTrace())));
    }
}
