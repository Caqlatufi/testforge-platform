package io.testforge.runorchestrator.ctrl;

import io.testforge.runorchestrator.task.model.TaskState;
import io.testforge.runorchestrator.task.model.TaskView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunGraphControllerTest {

    @Test
    void cancelledTaskMustNotBeRenderedAsSucceeded() {
        TaskView cancelled = task(TaskState.CANCELLED, true);

        assertThat(RunGraphController.aggregate(List.of(cancelled))).isEqualTo("CANCELLED");
        assertThat(RunGraphController.aggregate(List.of(task(TaskState.SUCCEEDED, true), cancelled)))
                .isEqualTo("CANCELLED");
    }

    @Test
    void optionalFailureIsRenderedAsWarningButRequiredFailureWins() {
        assertThat(RunGraphController.aggregate(List.of(
                task(TaskState.SUCCEEDED, true), task(TaskState.FAILED, false))))
                .isEqualTo("COMPLETED_WITH_WARNINGS");
        assertThat(RunGraphController.aggregate(List.of(
                task(TaskState.CANCELLED, true), task(TaskState.FAILED, true))))
                .isEqualTo("FAILED");
    }

    private TaskView task(TaskState state, boolean required) {
        TaskView task = mock(TaskView.class);
        when(task.state()).thenReturn(state);
        when(task.required()).thenReturn(required);
        return task;
    }
}
