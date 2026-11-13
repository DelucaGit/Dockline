package io.github.delucagit.dockline.status;

import io.github.delucagit.dockline.token.Source;
import io.swagger.v3.oas.annotations.media.Schema;

/** The connection state of one source, with a short explanation for the user when there is something to say. */
@Schema(description = "Connection state of one source")
public record SourceStatus(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Source source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ConnectionState state,
        @Schema(description = "Why the state is what it is (always for LIMITED and ERROR; may be set otherwise, "
                        + "e.g. 'Connected as octocat'); never contains a token",
                nullable = true) String message,
        @Schema(description = "Only while the source asked Dockline to wait (LIMITED after a 429 with Retry-After): "
                        + "whole seconds left before Dockline asks it again, at least 1; null otherwise",
                nullable = true, example = "23") Integer retryAfterSeconds) {

    public SourceStatus(Source source, ConnectionState state, String message) {
        this(source, state, message, null);
    }

    public static SourceStatus of(Source source, ConnectionState state) {
        return new SourceStatus(source, state, null);
    }
}
