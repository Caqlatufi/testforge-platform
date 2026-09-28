package io.testforge.casecatalog.testcase.ctrl;

import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.model.CaseScope;
import io.testforge.casecatalog.testcase.model.ExecutionRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record CreateTestCaseRequest(
        UUID targetId,
        @NotBlank @Size(max = 200) String name,
        @NotNull TestCaseKind kind,
        CaseScope scope,
        @NotNull Map<String, Object> parameters,
        @Size(max = 50) Set<@NotBlank @Size(max = 64) String> tags,
        @NotNull @Min(1) @Max(86_400) Integer timeoutSeconds,
        ExecutionRequirement executionRequirement
) {
}
