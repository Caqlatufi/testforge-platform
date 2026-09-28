package io.testforge.casecatalog.testcase.model;

public record CreateScriptVersionCommand(
        ScriptRunner runner,
        String sourceRef,
        String checksum
) {
}
