package io.testforge.dispatcher.outbox.service;

public class OutboxIdempotencyConflictException extends RuntimeException {

    public OutboxIdempotencyConflictException(String message) {
        super(message);
    }
}
