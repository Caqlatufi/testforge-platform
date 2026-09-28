package io.testforge.casecatalog.workflow.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.draft.WorkflowDraft;

import java.util.Objects;

final class WorkflowDraftJsonCodec {

    private final ObjectMapper objectMapper;

    WorkflowDraftJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    String write(WorkflowDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException exception) {
            throw new WorkflowValidationException("Workflow 草稿必须可以序列化为 JSON");
        }
    }

    WorkflowDraft read(String json) {
        try {
            return objectMapper.readValue(json, WorkflowDraft.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Workflow 草稿数据损坏", exception);
        }
    }
}
