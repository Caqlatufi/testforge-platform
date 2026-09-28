package io.testforge.runorchestrator.task.model;

import io.testforge.casecatalog.testcase.model.InteractionMode;

/** Task 消耗的执行资源类型。 */
public enum ResourceMode {
    PROCESS_POOL,
    EXCLUSIVE_DEVICE;

    public static ResourceMode fromRunner(String runner) {
        return "airtest".equalsIgnoreCase(runner) ? EXCLUSIVE_DEVICE : PROCESS_POOL;
    }

    public static ResourceMode fromInteraction(InteractionMode interaction, String legacyRunner) {
        if (interaction == null) {
            return fromRunner(legacyRunner);
        }
        return interaction == InteractionMode.UI ? EXCLUSIVE_DEVICE : PROCESS_POOL;
    }
}
