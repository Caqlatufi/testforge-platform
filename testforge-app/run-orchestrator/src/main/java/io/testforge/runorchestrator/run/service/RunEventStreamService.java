package io.testforge.runorchestrator.run.service;

import io.testforge.runorchestrator.run.model.RunView;
import io.testforge.common.event.ExecutionEventQueryPort;
import io.testforge.common.event.ExecutionEventView;
import jakarta.annotation.PreDestroy;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/** Snapshot-correcting SSE stream. The first and every changed Run version are sent as facts. */
public class RunEventStreamService {
    private final RunTaskService runs;
    private final ExecutionEventQueryPort events;
    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sentVersions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sentEventIds = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    public RunEventStreamService(RunTaskService runs) {
        this(runs, ExecutionEventQueryPort.noop());
    }

    public RunEventStreamService(RunTaskService runs, ExecutionEventQueryPort events) {
        this.runs = runs;
        this.events = events;
        ThreadFactory threads = task -> {
            Thread thread = new Thread(task, "run-sse-events");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(threads);
        scheduler.scheduleWithFixedDelay(this::broadcastChanges, 250, 250, TimeUnit.MILLISECONDS);
    }

    public SseEmitter subscribe(UUID runId, String lastEventId) {
        RunView snapshot = runs.getRun(runId);
        SseEmitter emitter = new SseEmitter(Duration.ofMinutes(30).toMillis());
        emitters.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
        long cursor = parseCursor(lastEventId);
        List<ExecutionEventView> history = events.history(runId, cursor, 500);
        history.forEach(event -> sendEvent(runId, emitter, event));
        if (!history.isEmpty()) {
            sentEventIds.merge(runId, history.getLast().id(), Math::max);
        }
        send(runId, emitter, snapshot, lastEventId == null ? "snapshot" : "resync");
        return emitter;
    }

    public RunView snapshot(UUID runId) {
        return runs.getRun(runId);
    }

    private void broadcastChanges() {
        for (UUID runId : List.copyOf(emitters.keySet())) {
            try {
                long cursor = sentEventIds.getOrDefault(runId, 0L);
                List<ExecutionEventView> nextEvents = events.history(runId, cursor, 500);
                for (ExecutionEventView event : nextEvents) {
                    emitters.getOrDefault(runId, List.of()).forEach(emitter -> sendEvent(runId, emitter, event));
                    sentEventIds.put(runId, event.id());
                }
                RunView snapshot = runs.getRun(runId);
                Long previous = sentVersions.put(runId, snapshot.version());
                if (previous == null || previous.longValue() != snapshot.version()) {
                    emitters.getOrDefault(runId, List.of()).forEach(
                            emitter -> send(runId, emitter, snapshot, "update")
                    );
                }
            } catch (RuntimeException ignored) {
                // A transient database failure must not terminate the single broadcaster thread.
            }
        }
    }

    private void send(UUID runId, SseEmitter emitter, RunView snapshot, String eventName) {
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .reconnectTime(1_000)
                    .data(snapshot));
            sentVersions.put(runId, snapshot.version());
        } catch (IOException | IllegalStateException error) {
            remove(runId, emitter);
            emitter.complete();
        }
    }

    private void sendEvent(UUID runId, SseEmitter emitter, ExecutionEventView event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(Long.toString(event.id()))
                    .name("execution-event")
                    .reconnectTime(1_000)
                    .data(event));
        } catch (IOException | IllegalStateException error) {
            remove(runId, emitter);
            emitter.complete();
        }
    }

    private long parseCursor(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return 0L;
        try {
            long cursor = Long.parseLong(lastEventId.trim());
            return Math.max(0L, cursor);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void remove(UUID runId, SseEmitter emitter) {
        List<SseEmitter> current = emitters.get(runId);
        if (current == null) return;
        current.remove(emitter);
        if (current.isEmpty()) {
            emitters.remove(runId);
            sentVersions.remove(runId);
            sentEventIds.remove(runId);
        }
    }

    @PreDestroy
    void close() {
        scheduler.shutdownNow();
        emitters.values().stream().flatMap(List::stream).forEach(SseEmitter::complete);
        emitters.clear();
    }
}
