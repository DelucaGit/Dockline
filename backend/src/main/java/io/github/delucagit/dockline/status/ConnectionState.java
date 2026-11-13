package io.github.delucagit.dockline.status;

import io.swagger.v3.oas.annotations.media.Schema;

/** How well Dockline is connected to a source. The sidebar shows it as a coloured dot. */
@Schema(enumAsRef = true, description = """
        CONNECTED: a token is stored and works (green). \
        LIMITED: works, but not fully, e.g. missing permissions or rate-limited (yellow). \
        ERROR: a token is stored but cannot be used, e.g. revoked, or the token store is unavailable (red). \
        NOT_CONNECTED: no token is stored (red).""")
public enum ConnectionState {
    CONNECTED,
    LIMITED,
    ERROR,
    NOT_CONNECTED
}
