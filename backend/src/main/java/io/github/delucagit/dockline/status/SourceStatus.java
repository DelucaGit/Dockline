package io.github.delucagit.dockline.status;

import io.github.delucagit.dockline.token.Source;
import io.swagger.v3.oas.annotations.media.Schema;

/** The connection state of one source, with a short explanation for the user when there is something to say. */
@Schema(description = "Connection state of one source")
public record SourceStatus(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Source source,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ConnectionState state,
        @Schema(description = "Why the state is what it is, for LIMITED and ERROR; never contains a token",
                nullable = true) String message) {

    public static SourceStatus of(Source source, ConnectionState state) {
        return new SourceStatus(source, state, null);
    }
}
