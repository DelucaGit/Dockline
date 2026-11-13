package io.github.delucagit.dockline.log;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** REST API of the Log section: recent warnings and errors. */
@RestController
@RequestMapping("/api/log")
@Tag(name = "Log", description = "Recent warnings and errors reported by the backend")
class LogController {

    private final ProblemLog problemLog;

    LogController(ProblemLog problemLog) {
        this.problemLog = problemLog;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "listLogEntries", summary = "Recent warnings and errors, newest first (at most 500)")
    List<LogEntry> list(
            @Parameter(description = "Only entries from this source") @RequestParam(required = false) LogSource source,
            @Parameter(description = "Only entries with this level") @RequestParam(required = false) LogLevel level) {
        return problemLog.recent(source, level);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "clearLogEntries", summary = "Clears the list (log files are kept)")
    @ApiResponse(responseCode = "204", description = "Cleared")
    void clear() {
        problemLog.clear();
    }
}
