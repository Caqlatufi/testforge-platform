package io.testforge.aidiagnosis.model;

import java.util.List;

public record ProviderDiagnosis(
        String category,
        double confidence,
        List<EvidenceCitation> evidence,
        List<String> suggestions,
        List<String> missingEvidence
) { }
