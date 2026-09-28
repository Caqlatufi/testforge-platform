package io.testforge.dispatcher.reliability.lease;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 一轮过期扫描的确定性结果。 */
public record LeaseReapResult(
        Instant scannedAt,
        int candidateCount,
        List<LostLease> lostLeases
) {

    public LeaseReapResult {
        Objects.requireNonNull(scannedAt, "scannedAt must not be null");
        if (candidateCount < 0) {
            throw new IllegalArgumentException("candidateCount must not be negative");
        }
        lostLeases = List.copyOf(Objects.requireNonNull(lostLeases, "lostLeases must not be null"));
        if (lostLeases.size() > candidateCount) {
            throw new IllegalArgumentException("lostLeases cannot exceed candidateCount");
        }
    }

    public int lostCount() {
        return lostLeases.size();
    }
}
