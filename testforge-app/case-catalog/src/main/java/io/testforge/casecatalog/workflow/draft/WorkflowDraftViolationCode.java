package io.testforge.casecatalog.workflow.draft;

/**
 * Workflow 草稿图的稳定校验错误码，供后续 API 接线和前端定位使用。
 */
public enum WorkflowDraftViolationCode {
    EMPTY_GRAPH,
    NULL_NODE,
    MISSING_NODE_ID,
    DUPLICATE_NODE_ID,
    MISSING_NODE_TYPE,
    MISSING_REFERENCE_ID,
    INVALID_REFERENCE_VERSION,
    MISSING_REQUIRED,
    INVALID_TIMEOUT,
    INVALID_POSITION,
    NULL_EDGE,
    MISSING_EDGE_ENDPOINT,
    DANGLING_EDGE,
    SELF_DEPENDENCY,
    DUPLICATE_EDGE,
    MISSING_EDGE_CONDITION,
    CYCLE
}
