package io.testforge.casecatalog.workflow.draft;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow 草稿节点。
 *
 * <p>{@code referenceId/referenceVersion} 根据 {@code type} 分别引用 Case 身份（YAML-first Case
 * 使用兼容值 1）、Suite 固定版本、Fixture Case 身份或已发布 Subflow 拓扑版本。字段约束由
 * {@link WorkflowDraftGraphValidator} 集中校验，使从编辑器反序列化得到的不完整草稿也能返回
 * 明确的领域错误。</p>
 */
public record WorkflowDraftNode(
        UUID id,
        WorkflowNodeType type,
        UUID referenceId,
        Integer referenceVersion,
        Boolean required,
        Integer timeoutSeconds,
        Map<String, Object> parameterOverrides,
        double positionX,
        double positionY
) {

    public WorkflowDraftNode {
        parameterOverrides = parameterOverrides == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(parameterOverrides));
    }
}
