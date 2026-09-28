package io.testforge.casecatalog.testcase.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum ScriptRunner {
    PYTEST_HTTP("pytest-http"),
    PLAYWRIGHT_WEB("playwright-web"),
    AIRTEST("airtest");

    private final String contractValue;

    ScriptRunner(String contractValue) {
        this.contractValue = contractValue;
    }

    @JsonValue
    public String contractValue() {
        return contractValue;
    }

    @JsonCreator
    public static ScriptRunner fromContractValue(String value) {
        return Arrays.stream(values())
                .filter(runner -> runner.contractValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的 runner: " + value));
    }
}
