package io.testforge.dispatcher.port.outbound;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;
import java.util.Locale;

/**
 * Worker 协议支持的平台值。
 */
public enum WorkerPlatform {
    LINUX,
    WINDOWS,
    ANDROID,
    IOS,
    ANY;

    @JsonValue
    public String contractValue() {
        return name();
    }

    @JsonCreator
    public static WorkerPlatform fromContractValue(String value) {
        String normalized = value == null ? null : value.toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(candidate -> candidate.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的 platform: " + value));
    }
}
