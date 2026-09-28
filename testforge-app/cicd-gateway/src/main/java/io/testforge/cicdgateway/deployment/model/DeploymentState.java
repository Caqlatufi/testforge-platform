package io.testforge.cicdgateway.deployment.model;

public enum DeploymentState {
    PENDING,
    TRIGGERED,
    BUILDING,
    READY,
    FAILED,
    EXPIRED;

    public boolean isTerminal() {
        return this == READY || this == FAILED || this == EXPIRED;
    }
}
