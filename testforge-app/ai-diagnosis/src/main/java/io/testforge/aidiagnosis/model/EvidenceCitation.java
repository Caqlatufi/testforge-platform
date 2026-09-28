package io.testforge.aidiagnosis.model;

import java.util.Objects;

public record EvidenceCitation(String evidenceId, String reason) {
    public EvidenceCitation {
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(reason, "reason");
    }
}
