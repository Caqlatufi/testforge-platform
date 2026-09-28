package io.testforge.casecatalog.testcase.ctrl;

import jakarta.validation.constraints.NotBlank;

public record CaseDefinitionRequest(@NotBlank String yaml) {
}
