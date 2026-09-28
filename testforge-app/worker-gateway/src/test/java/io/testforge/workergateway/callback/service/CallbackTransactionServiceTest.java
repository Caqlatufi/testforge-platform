package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.entity.CallbackReceiptEntity;
import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.CallbackDisposition;
import io.testforge.workergateway.callback.model.CallbackStatus;
import io.testforge.workergateway.callback.repo.CallbackReceiptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CallbackTransactionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");
    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CALLBACK_KEY = UUID.fromString("b0000000-0000-4000-8000-000000000001");
    private static final UUID TASK_ID = UUID.fromString("30000000-0000-4000-8000-000000000001");

    @Mock
    private CallbackReceiptRepository receiptRepository;

    @Mock
    private AttemptExecutionGateway executionGateway;

    @Mock
    private TestResultPublisher resultPublisher;

    private CallbackTransactionService service;

    @BeforeEach
    void setUp() {
        service = new CallbackTransactionService(
                receiptRepository, executionGateway, resultPublisher, Duration.ofSeconds(30)
        );
    }

    @Test
    void acceptsFirstCallbackAndPublishesExactlyOneResult() {
        AttemptCallbackRequest request = passedRequest();
        when(receiptRepository.findByAttemptIdAndCallbackKey(ATTEMPT_ID, CALLBACK_KEY))
                .thenReturn(Optional.empty());
        when(receiptRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executionGateway.complete(
                eq(ATTEMPT_ID), eq("worker-1"), eq(request.leaseToken()),
                eq(CallbackStatus.PASSED), eq(NOW), eq(NOW.plusSeconds(30))
        )).thenReturn(AttemptCompletion.accepted(TASK_ID, CallbackStatus.PASSED));

        var response = service.process(request, "a".repeat(64), NOW);

        assertThat(response.disposition()).isEqualTo(CallbackDisposition.ACCEPTED);
        assertThat(response.originalDisposition()).isEqualTo(CallbackDisposition.ACCEPTED);
        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.resultStatus()).isEqualTo(CallbackStatus.PASSED);
        assertThat(response.reason()).isNull();
        assertThat(response.receivedAt()).isEqualTo(NOW);
        ArgumentCaptor<CallbackReceiptEntity> receipt = ArgumentCaptor.forClass(CallbackReceiptEntity.class);
        verify(receiptRepository).saveAndFlush(receipt.capture());
        assertThat(receipt.getValue().getDisposition()).isEqualTo(CallbackDisposition.ACCEPTED);
        verify(resultPublisher).publish(TASK_ID, request, CallbackStatus.PASSED);
    }

    @Test
    void duplicateReturnsOriginalReceiptTimeWithoutReapplyingTerminalState() {
        CallbackReceiptEntity existing = existingReceipt("a".repeat(64));
        existing.accept(TASK_ID, CallbackStatus.PASSED);
        when(receiptRepository.findByAttemptIdAndCallbackKey(ATTEMPT_ID, CALLBACK_KEY))
                .thenReturn(Optional.of(existing));

        var response = service.process(passedRequest(), "a".repeat(64), NOW.plusSeconds(10));

        assertThat(response.disposition()).isEqualTo(CallbackDisposition.DUPLICATE);
        assertThat(response.originalDisposition()).isEqualTo(CallbackDisposition.ACCEPTED);
        assertThat(response.taskId()).isEqualTo(TASK_ID);
        assertThat(response.resultStatus()).isEqualTo(CallbackStatus.PASSED);
        assertThat(response.reason()).isNull();
        assertThat(response.receivedAt()).isEqualTo(NOW);
        verifyNoInteractions(executionGateway, resultPublisher);
        verify(receiptRepository, never()).saveAndFlush(any());
    }

    @Test
    void sameKeyWithDifferentPayloadIsConflictBeforeAnyStateChange() {
        CallbackReceiptEntity existing = existingReceipt("a".repeat(64));
        existing.accept(TASK_ID, CallbackStatus.PASSED);
        when(receiptRepository.findByAttemptIdAndCallbackKey(ATTEMPT_ID, CALLBACK_KEY))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.process(passedRequest(), "b".repeat(64), NOW))
                .isInstanceOf(CallbackIdempotencyConflictException.class);

        verifyNoInteractions(executionGateway, resultPublisher);
    }

    @Test
    void staleCallbackIsAuditedWithoutPublishingAResult() {
        AttemptCallbackRequest request = passedRequest();
        when(receiptRepository.findByAttemptIdAndCallbackKey(ATTEMPT_ID, CALLBACK_KEY))
                .thenReturn(Optional.empty());
        when(receiptRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(executionGateway.complete(any(), any(), any(), any(), any(), any()))
                .thenReturn(AttemptCompletion.stale());

        var response = service.process(request, "a".repeat(64), NOW);

        assertThat(response.disposition()).isEqualTo(CallbackDisposition.STALE);
        assertThat(response.originalDisposition()).isEqualTo(CallbackDisposition.STALE);
        assertThat(response.taskId()).isNull();
        assertThat(response.resultStatus()).isNull();
        assertThat(response.reason()).isEqualTo("STALE_CALLBACK");
        ArgumentCaptor<CallbackReceiptEntity> receipt = ArgumentCaptor.forClass(CallbackReceiptEntity.class);
        verify(receiptRepository).saveAndFlush(receipt.capture());
        assertThat(receipt.getValue().getDisposition()).isEqualTo(CallbackDisposition.STALE);
        assertThat(receipt.getValue().getReason()).isEqualTo("STALE_CALLBACK");
        verifyNoInteractions(resultPublisher);
    }

    @Test
    void duplicateStaleCallbackReturnsTheOriginalStaleResult() {
        CallbackReceiptEntity existing = existingReceipt("a".repeat(64));
        existing.stale();
        when(receiptRepository.findByAttemptIdAndCallbackKey(ATTEMPT_ID, CALLBACK_KEY))
                .thenReturn(Optional.of(existing));

        var response = service.process(passedRequest(), "a".repeat(64), NOW.plusSeconds(10));

        assertThat(response.disposition()).isEqualTo(CallbackDisposition.DUPLICATE);
        assertThat(response.originalDisposition()).isEqualTo(CallbackDisposition.STALE);
        assertThat(response.taskId()).isNull();
        assertThat(response.resultStatus()).isNull();
        assertThat(response.reason()).isEqualTo("STALE_CALLBACK");
        assertThat(response.receivedAt()).isEqualTo(NOW);
        verifyNoInteractions(executionGateway, resultPublisher);
        verify(receiptRepository, never()).saveAndFlush(any());
    }

    private CallbackReceiptEntity existingReceipt(String payloadHash) {
        return new CallbackReceiptEntity(
                UUID.randomUUID(), ATTEMPT_ID, CALLBACK_KEY, payloadHash, NOW
        );
    }

    private AttemptCallbackRequest passedRequest() {
        return new AttemptCallbackRequest(
                "1.0.0",
                CALLBACK_KEY,
                ATTEMPT_ID,
                UUID.fromString("60000000-0000-4000-8000-000000000001"),
                "worker-1",
                CallbackStatus.PASSED,
                NOW,
                500L,
                "passed",
                null,
                List.of(),
                Map.of()
        );
    }
}
