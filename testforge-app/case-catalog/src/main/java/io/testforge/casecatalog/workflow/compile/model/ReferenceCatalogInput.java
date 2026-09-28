package io.testforge.casecatalog.workflow.compile.model;

import java.util.Map;

public record ReferenceCatalogInput(
        Map<ReferenceKey, CaseReferenceInput> cases,
        Map<ReferenceKey, SuiteReferenceInput> suites,
        Map<ReferenceKey, SubflowReferenceInput> subflows
) {

    public ReferenceCatalogInput {
        cases = cases == null ? Map.of() : Map.copyOf(cases);
        suites = suites == null ? Map.of() : Map.copyOf(suites);
        subflows = subflows == null ? Map.of() : Map.copyOf(subflows);
    }

    public static ReferenceCatalogInput empty() {
        return new ReferenceCatalogInput(Map.of(), Map.of(), Map.of());
    }
}
