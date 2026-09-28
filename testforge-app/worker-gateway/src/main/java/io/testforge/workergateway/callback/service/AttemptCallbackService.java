package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.CallbackReceiptResponse;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class AttemptCallbackService {

    private final CallbackTransactionService transactionService;
    private final CallbackPayloadHasher payloadHasher;

    public AttemptCallbackService(
            CallbackTransactionService transactionService,
            CallbackPayloadHasher payloadHasher
    ) {
        this.transactionService = Objects.requireNonNull(transactionService, "transactionService 不能为空");
        this.payloadHasher = Objects.requireNonNull(payloadHasher, "payloadHasher 不能为空");
    }

    public CallbackReceiptResponse callback(
            UUID pathAttemptId,
            AttemptCallbackRequest request,
            Instant receivedAt
    ) {
        request.validateFor(pathAttemptId);
        String payloadHash = payloadHasher.hash(request);
        try {
            return transactionService.process(request, payloadHash, receivedAt);
        } catch (DataIntegrityViolationException concurrentInsert) {
            return transactionService.replayAfterConcurrentInsert(
                            request.attemptId(), request.callbackKey(), payloadHash
                    )
                    .orElseThrow(() -> concurrentInsert);
        }
    }
}
