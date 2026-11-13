package io.github.delucagit.dockline.status;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** REST API for the connection state of each source (the sidebar's status dots). */
@RestController
@RequestMapping("/api/status")
@Tag(name = "Status", description = "Connection state of GitHub and Hostinger")
class StatusController {

    private final SourceStatuses statuses;

    StatusController(SourceStatuses statuses) {
        this.statuses = statuses;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getStatus", summary = "Connection state of every source")
    StatusResponse get() {
        return new StatusResponse(statuses.all());
    }

    /** One entry per source, always all sources, in a fixed order (GITHUB, HOSTINGER). */
    record StatusResponse(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<SourceStatus> sources) {}
}
