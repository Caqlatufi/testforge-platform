package io.testforge.casecatalog.suite.ctrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateTestSuiteRequest(
        @NotNull UUID targetId,
        @NotBlank @Size(max = 200) String name,
        @NotEmpty @Size(max = 1_000) List<@NotNull UUID> caseIds,
        @NotNull @Size(max = 32) List<@NotBlank @Size(max = 64) String> tags,
        @NotNull @Size(max = 100) Map<@NotBlank @Size(max = 100) String, @NotNull Object> parameterBindings
) {
}
