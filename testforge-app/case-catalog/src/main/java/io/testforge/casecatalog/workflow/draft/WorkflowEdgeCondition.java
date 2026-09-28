package io.testforge.casecatalog.workflow.draft;

/**
 * 前置节点满足何种状态后，后继节点才可以被释放。
 */
public enum WorkflowEdgeCondition {
    ON_SUCCESS,
    ON_COMPLETION
}
