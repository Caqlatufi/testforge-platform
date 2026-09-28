package io.testforge.casecatalog.workflow.compile.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.casecatalog.workflow.compile.model.CompiledWorkflowSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 生成与输入集合迭代顺序无关的快照 JSON 和内容校验和。 */
public final class WorkflowSnapshotJsonCodec {

    private final ObjectMapper objectMapper;

    public WorkflowSnapshotJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String write(CompiledWorkflowSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException exception) {
            throw new WorkflowCompileException("编译快照无法序列化为 JSON");
        }
    }

    public CompiledWorkflowSnapshot read(String json) {
        try {
            return objectMapper.readValue(json, CompiledWorkflowSnapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("已发布 Workflow 编译快照数据损坏", exception);
        }
    }

    public String checksum(String canonicalJson) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
