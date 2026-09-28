package io.testforge.casecatalog.testcase.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * Case 级执行资源契约。DAG 边只表达依赖，不会把本节点的资源需求传播给后继节点。
 */
public record ExecutionRequirement(
        String executorType,
        InteractionMode interaction,
        Set<String> capabilities,
        String resourceProfile,
        LeaseScope leaseScope,
        String sessionKey
) {
    public ExecutionRequirement {
        capabilities = capabilities == null ? Set.of() : Set.copyOf(new TreeSet<>(capabilities));
        leaseScope = leaseScope == null ? LeaseScope.CASE : leaseScope;
        sessionKey = sessionKey == null || sessionKey.isBlank() ? null : sessionKey.trim();
    }

    public static ExecutionRequirement legacyDefault(ScriptRunner runner) {
        return legacyDefault(runner == null ? null : runner.contractValue());
    }

    public static ExecutionRequirement legacyDefault(String executorType) {
        String normalized = executorType == null || executorType.isBlank()
                ? ScriptRunner.PYTEST_HTTP.contractValue()
                : executorType.trim();
        boolean ui = ScriptRunner.AIRTEST.contractValue().equalsIgnoreCase(normalized);
        return new ExecutionRequirement(
                normalized,
                ui ? InteractionMode.UI : InteractionMode.HEADLESS,
                Set.of(),
                ui ? "ui-default" : "script-small",
                LeaseScope.CASE,
                null
        );
    }
}
