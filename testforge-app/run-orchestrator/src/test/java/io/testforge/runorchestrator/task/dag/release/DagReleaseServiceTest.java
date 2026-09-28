package io.testforge.runorchestrator.task.dag.release;

import io.testforge.casecatalog.workflow.compile.model.DependencyCondition;
import io.testforge.runorchestrator.task.model.TaskState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DagReleaseServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T08:00:00Z");
    private static final UUID RUN_ID = id("run");

    @Test
    void shouldReleaseParallelRootsOnlyOnce() {
        UUID firstRoot = id("first-root");
        UUID secondRoot = id("second-root");
        UUID successor = id("successor");
        InMemoryStateStore store = store(
                List.of(
                        task(firstRoot, TaskState.CREATED, true),
                        task(secondRoot, TaskState.CREATED, false),
                        task(successor, TaskState.WAITING_DEPENDENCY, true)
                ),
                List.of(edge(firstRoot, successor, DependencyCondition.ON_SUCCESS))
        );
        DagReleaseService service = service(store);

        DagReleaseResult first = service.releaseRoots(RUN_ID);
        DagReleaseResult duplicate = service.releaseRoots(RUN_ID);

        assertThat(first.releasedTaskIds()).containsExactlyInAnyOrder(firstRoot, secondRoot);
        assertThat(first.blockedTaskIds()).isEmpty();
        assertThat(duplicate.isEmpty()).isTrue();
        assertThat(store.state(firstRoot)).isEqualTo(TaskState.QUEUED);
        assertThat(store.state(secondRoot)).isEqualTo(TaskState.QUEUED);
        assertThat(store.state(successor)).isEqualTo(TaskState.WAITING_DEPENDENCY);
        assertThat(store.transitionCount(firstRoot)).isEqualTo(1);
        assertThat(store.transitionCount(secondRoot)).isEqualTo(1);
    }

    @Test
    void shouldWaitForEveryParallelPredecessorBeforeReleasingJoin() {
        UUID first = id("parallel-first");
        UUID second = id("parallel-second");
        UUID join = id("parallel-join");
        InMemoryStateStore store = store(
                List.of(
                        task(first, TaskState.SUCCEEDED, true),
                        task(second, TaskState.RUNNING, true),
                        task(join, TaskState.WAITING_DEPENDENCY, true)
                ),
                List.of(
                        edge(first, join, DependencyCondition.ON_SUCCESS),
                        edge(second, join, DependencyCondition.ON_COMPLETION)
                )
        );
        DagReleaseService service = service(store);

        assertThat(service.releaseSuccessors(RUN_ID, first).isEmpty()).isTrue();
        assertThat(store.state(join)).isEqualTo(TaskState.WAITING_DEPENDENCY);

        store.forceState(second, TaskState.SUCCEEDED);
        DagReleaseResult released = service.releaseSuccessors(RUN_ID, second);

        assertThat(released.releasedTaskIds()).containsExactly(join);
        assertThat(store.state(join)).isEqualTo(TaskState.QUEUED);
    }

    @Test
    void shouldHonorEdgeConditionsAndPropagateBlockedWithoutStoppingIndependentBranch() {
        UUID optionalFailure = id("optional-failure");
        UUID cleanup = id("cleanup-on-completion");
        UUID successOnly = id("success-only");
        UUID blockedChild = id("blocked-child");
        UUID cleanupAfterBlocked = id("cleanup-after-blocked");
        UUID independent = id("independent");
        InMemoryStateStore store = store(
                List.of(
                        task(optionalFailure, TaskState.FAILED, false),
                        task(cleanup, TaskState.WAITING_DEPENDENCY, true),
                        task(successOnly, TaskState.WAITING_DEPENDENCY, true),
                        task(blockedChild, TaskState.WAITING_DEPENDENCY, true),
                        task(cleanupAfterBlocked, TaskState.WAITING_DEPENDENCY, true),
                        task(independent, TaskState.QUEUED, true)
                ),
                List.of(
                        edge(optionalFailure, cleanup, DependencyCondition.ON_COMPLETION),
                        edge(optionalFailure, successOnly, DependencyCondition.ON_SUCCESS),
                        edge(successOnly, blockedChild, DependencyCondition.ON_SUCCESS),
                        edge(successOnly, cleanupAfterBlocked, DependencyCondition.ON_COMPLETION)
                )
        );

        DagReleaseResult result = service(store).releaseSuccessors(RUN_ID, optionalFailure);

        assertThat(result.releasedTaskIds()).containsExactlyInAnyOrder(cleanup, cleanupAfterBlocked);
        assertThat(result.blockedTaskIds()).containsExactlyInAnyOrder(successOnly, blockedChild);
        assertThat(store.state(independent)).isEqualTo(TaskState.QUEUED);
        assertThat(store.task(optionalFailure).required()).isFalse();
        assertThat(store.applied(successOnly).blockedByTaskId()).isEqualTo(optionalFailure);
        assertThat(store.applied(blockedChild).blockedByTaskId()).isEqualTo(successOnly);
        assertThat(store.applied(successOnly).blockedReason())
                .contains("ON_SUCCESS")
                .contains("FAILED");
    }

    @Test
    void shouldResumePropagationFromPreviouslyBlockedTask() {
        UUID trigger = id("recovery-trigger");
        UUID previouslyBlocked = id("previously-blocked");
        UUID descendant = id("recovery-descendant");
        InMemoryStateStore store = store(
                List.of(
                        task(trigger, TaskState.FAILED, true),
                        task(previouslyBlocked, TaskState.BLOCKED, true),
                        task(descendant, TaskState.WAITING_DEPENDENCY, true)
                ),
                List.of(edge(previouslyBlocked, descendant, DependencyCondition.ON_SUCCESS))
        );

        DagReleaseResult result = service(store).releaseSuccessors(RUN_ID, trigger);

        assertThat(result.blockedTaskIds()).containsExactly(descendant);
        assertThat(store.applied(descendant).blockedByTaskId()).isEqualTo(previouslyBlocked);
    }

    @Test
    void shouldReleaseSuccessorOnceWhenPredecessorsCompleteConcurrently() throws Exception {
        UUID first = id("concurrent-first");
        UUID second = id("concurrent-second");
        UUID successor = id("concurrent-successor");
        InMemoryStateStore store = store(
                List.of(
                        task(first, TaskState.SUCCEEDED, true),
                        task(second, TaskState.SUCCEEDED, true),
                        task(successor, TaskState.WAITING_DEPENDENCY, true)
                ),
                List.of(
                        edge(first, successor, DependencyCondition.ON_SUCCESS),
                        edge(second, successor, DependencyCondition.ON_SUCCESS)
                )
        );
        DagReleaseService service = service(store);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<DagReleaseResult>> calls = new ArrayList<>();

        try (var executor = Executors.newFixedThreadPool(8)) {
            for (int index = 0; index < 32; index++) {
                UUID trigger = index % 2 == 0 ? first : second;
                calls.add(CompletableFuture.supplyAsync(() -> {
                    await(start);
                    return service.releaseSuccessors(RUN_ID, trigger);
                }, executor));
            }
            start.countDown();
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new))
                    .get(10, TimeUnit.SECONDS);
        }

        long releaseActions = calls.stream()
                .map(CompletableFuture::join)
                .flatMap(result -> result.releasedTaskIds().stream())
                .filter(successor::equals)
                .count();
        assertThat(releaseActions).isEqualTo(1);
        assertThat(store.transitionCount(successor)).isEqualTo(1);
        assertThat(store.state(successor)).isEqualTo(TaskState.QUEUED);
    }

    private static DagReleaseService service(InMemoryStateStore store) {
        return new DagReleaseService(store, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static InMemoryStateStore store(
            List<DagTaskSnapshot> tasks,
            List<DagDependency> dependencies
    ) {
        return new InMemoryStateStore(RUN_ID, tasks, dependencies);
    }

    private static DagTaskSnapshot task(UUID taskId, TaskState state, boolean required) {
        return new DagTaskSnapshot(taskId, state, required, 0);
    }

    private static DagDependency edge(
            UUID predecessor,
            UUID successor,
            DependencyCondition condition
    ) {
        return new DagDependency(predecessor, successor, condition);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("并发释放未按时开始");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static final class InMemoryStateStore implements DagReleaseStateStore {

        private final UUID runId;
        private final Map<UUID, DagTaskSnapshot> tasks = new ConcurrentHashMap<>();
        private final List<DagDependency> dependencies;
        private final Map<UUID, DagTaskTransition> applied = new ConcurrentHashMap<>();
        private final Map<UUID, AtomicInteger> transitionCounts = new ConcurrentHashMap<>();

        private InMemoryStateStore(
                UUID runId,
                List<DagTaskSnapshot> tasks,
                List<DagDependency> dependencies
        ) {
            this.runId = runId;
            tasks.forEach(task -> this.tasks.put(task.taskId(), task));
            this.dependencies = List.copyOf(dependencies);
        }

        @Override
        public DagRunSnapshot load(UUID requestedRunId) {
            assertThat(requestedRunId).isEqualTo(runId);
            return new DagRunSnapshot(runId, List.copyOf(tasks.values()), dependencies);
        }

        @Override
        public boolean compareAndSet(UUID requestedRunId, DagTaskTransition transition) {
            assertThat(requestedRunId).isEqualTo(runId);
            AtomicBoolean changed = new AtomicBoolean();
            tasks.compute(transition.taskId(), (taskId, current) -> {
                if (current == null
                        || current.state() != transition.expectedState()
                        || current.version() != transition.expectedVersion()) {
                    return current;
                }
                changed.set(true);
                applied.put(taskId, transition);
                transitionCounts.computeIfAbsent(taskId, ignored -> new AtomicInteger())
                        .incrementAndGet();
                return new DagTaskSnapshot(
                        taskId,
                        transition.targetState(),
                        current.required(),
                        current.version() + 1
                );
            });
            return changed.get();
        }

        private void forceState(UUID taskId, TaskState state) {
            tasks.compute(taskId, (ignored, current) -> new DagTaskSnapshot(
                    current.taskId(),
                    state,
                    current.required(),
                    current.version() + 1
            ));
        }

        private DagTaskSnapshot task(UUID taskId) {
            return tasks.get(taskId);
        }

        private TaskState state(UUID taskId) {
            return task(taskId).state();
        }

        private DagTaskTransition applied(UUID taskId) {
            return applied.get(taskId);
        }

        private int transitionCount(UUID taskId) {
            return transitionCounts.getOrDefault(taskId, new AtomicInteger()).get();
        }
    }
}
