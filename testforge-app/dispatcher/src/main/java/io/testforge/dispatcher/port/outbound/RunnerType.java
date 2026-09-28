package io.testforge.dispatcher.port.outbound;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * Worker 协议支持的执行器类型。
 */
public enum RunnerType {
    PYTEST_HTTP("pytest-http"),
    PLAYWRIGHT_WEB("playwright-web"),
    AIRTEST("airtest");

    private final String contractValue;

    RunnerType(String contractValue) {
        this.contractValue = contractValue;
    }

    @JsonValue
    public String contractValue() {
        return contractValue;
    }

    @JsonCreator
    public static RunnerType fromContractValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.contractValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的 runner: " + value));
    }
}
