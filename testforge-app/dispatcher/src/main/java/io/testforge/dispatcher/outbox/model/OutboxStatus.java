package io.testforge.dispatcher.outbox.model;

public enum OutboxStatus {
    PENDING,
    CLAIMED,
    PUBLISHED
}
