package io.testforge.casecatalog.testcase.ctrl;

import io.testforge.casecatalog.testcase.model.ScriptRunner;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateScriptVersionRequest(
        @NotNull ScriptRunner runner,
        @NotBlank @Size(max = 1024) String sourceRef,
        @NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String checksum
) {
}
