package io.testforge.dispatcher.relay;

public record RelayBatchResult(int claimed, int published, int failed, int ownershipLost) {

    public static RelayBatchResult unavailable() {
        return new RelayBatchResult(0, 0, 0, 0);
    }
}
