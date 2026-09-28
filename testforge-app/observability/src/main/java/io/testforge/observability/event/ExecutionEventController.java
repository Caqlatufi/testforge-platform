package io.testforge.observability.event;

import io.testforge.common.event.ExecutionEventView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs/{runId}/events")
public class ExecutionEventController {
    private final ExecutionEventService service;

    public ExecutionEventController(ExecutionEventService service) { this.service = service; }

    @GetMapping("/history")
    public Map<String, Object> history(
            @PathVariable UUID runId,
            @RequestParam(defaultValue = "0") long afterEventId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        List<ExecutionEventView> events = service.history(runId, afterEventId, limit);
        long nextEventId = events.isEmpty() ? afterEventId : events.getLast().id();
        return Map.of("data", Map.of("events", events, "nextEventId", nextEventId));
    }
}
