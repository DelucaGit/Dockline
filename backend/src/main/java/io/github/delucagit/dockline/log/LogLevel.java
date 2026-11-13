package io.github.delucagit.dockline.log;

import io.swagger.v3.oas.annotations.media.Schema;

/** How serious a log entry is. Only warnings and errors are kept for the Log section. */
@Schema(enumAsRef = true)
public enum LogLevel {
    WARN,
    ERROR
}
