package io.github.delucagit.dockline.token;

import io.swagger.v3.oas.annotations.media.Schema;

/** An external service Dockline connects to with a token. */
@Schema(enumAsRef = true)
public enum Source {
    GITHUB,
    HOSTINGER
}
