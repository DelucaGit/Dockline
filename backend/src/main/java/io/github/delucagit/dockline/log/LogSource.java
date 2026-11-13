package io.github.delucagit.dockline.log;

import io.swagger.v3.oas.annotations.media.Schema;

/** Where a problem came from: an external API, or the backend itself. */
@Schema(enumAsRef = true)
public enum LogSource {
    GITHUB,
    HOSTINGER,
    BACKEND
}
