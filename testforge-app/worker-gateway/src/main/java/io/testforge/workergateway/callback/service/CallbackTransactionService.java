package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.entity.CallbackReceiptEntity;
import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.CallbackDisposition;
import io.testforge.workergateway.callback.model.CallbackReceiptResponse;
import io.testforge.workergateway.callback.repo.CallbackReceiptRepository;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class CallbackTransactionService {

    private final CallbackReceiptRepository receiptRepository;
    private final AttemptExecutionGateway executionGateway;
    private final TestResultPublisher resultPublisher;
    private final Duration leaseDuration;
    private final DeviceLeaseService deviceLeaseService;

    public CallbackTransactionService(
            CallbackReceiptRepository receiptRepository,
            AttemptExecutionGateway executionGateway,
            TestResultPublisher resultPublisher,
            Duration leaseDuration
    ) {
        this(receiptRepository, executionGateway, resultPublisher, leaseDuration, null);
    }

    public CallbackTransactionService(
            CallbackReceiptRepository receiptRepository,
            AttemptExecutionGateway executionGateway,
            TestResultPublisher resultPublisher,
            Duration leaseDuration,
            DeviceLeaseService deviceLeaseService
    ) {
        this.receiptRepository = Objects.requireNonNull(receiptRepository, "receiptRepository 不能为空");
        this.executionGateway = Objects.requireNonNull(executionGateway, "executionGateway 不能为空");
        this.resultPublisher = Objects.requireNonNull(resultPublisher, "resultPublisher 不能为空");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration 不能为空");
        this.deviceLeaseService = deviceLeaseService;
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration 必须大于 0");
        }
    }

    /**
     * Receipt 与 Attempt/Task 终态共用调用方事务。先占用幂等键，再尝试推进终态；
     * 迟到回调也保存为 STALE receipt，作为 STALE_CALLBACK 审计证据。
     */
    @Transactional
    public CallbackReceiptResponse process(
            AttemptCallbackRequest request,
            String payloadHash,
            Instant receivedAt
    ) {
        Optional<CallbackReceiptEntity> existing = receiptRepository.findByAttemptIdAndCallbackKey(
                request.attemptId(), request.callbackKey()
        );
        if (existing.isPresent()) {
            return replay(existing.get(), payloadHash);
        }

        CallbackReceiptEntity receipt = new CallbackReceiptEntity(
                UUID.randomUUID(),
                request.attemptId(),
                request.callbackKey(),
                payloadHash,
                receivedAt
        );
        receiptRepository.saveAndFlush(receipt);

        AttemptCompletion completion = executionGateway.complete(
                request.attemptId(),
                request.workerId(),
                request.leaseToken(),
                request.status(),
                receivedAt,
                receivedAt.plus(leaseDuration)
        );
        if (!completion.accepted()) {
            receipt.stale();
            return receipt.response(CallbackDisposition.STALE);
        }

        resultPublisher.publish(completion.taskId(), request, completion.effectiveStatus());
        if (deviceLeaseService != null) {
            deviceLeaseService.releaseTerminal(request.attemptId());
        }
        receipt.accept(completion.taskId(), completion.effectiveStatus());
        return receipt.response(CallbackDisposition.ACCEPTED);
    }

    /** 在唯一键并发失败后的新事务中读取胜者，保证并发重复返回同一原始 receipt 时间。 */
    @Transactional(readOnly = true)
    public Optional<CallbackReceiptResponse> replayAfterConcurrentInsert(
            UUID attemptId,
            UUID callbackKey,
            String payloadHash
    ) {
        return receiptRepository.findByAttemptIdAndCallbackKey(attemptId, callbackKey)
                .map(receipt -> replay(receipt, payloadHash));
    }

    private CallbackReceiptResponse replay(CallbackReceiptEntity receipt, String payloadHash) {
        if (!receipt.getPayloadHash().equals(payloadHash)) {
            throw new CallbackIdempotencyConflictException(
                    receipt.getAttemptId(), receipt.getCallbackKey()
            );
        }
        return receipt.response(CallbackDisposition.DUPLICATE);
    }
}
