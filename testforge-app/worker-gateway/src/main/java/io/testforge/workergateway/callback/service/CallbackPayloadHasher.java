package io.testforge.workergateway.callback.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.testforge.workergateway.callback.model.AttemptCallbackRequest;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public class CallbackPayloadHasher {

    private final ObjectMapper canonicalMapper;

    public CallbackPayloadHasher(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper 不能为空");
        this.canonicalMapper = objectMapper.copy()
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    }

    public String hash(AttemptCallbackRequest request) {
        try {
            byte[] canonicalPayload = canonicalMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(sha256().digest(canonicalPayload));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("回调载荷无法生成稳定哈希", exception);
        }
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }
}
