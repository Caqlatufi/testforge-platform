package io.testforge.workergateway.callback.service;

import io.testforge.workergateway.callback.model.AttemptCallbackRequest;
import io.testforge.workergateway.callback.model.CallbackDisposition;
import io.testforge.workergateway.callback.model.CallbackReceiptResponse;
import io.testforge.workergateway.callback.model.CallbackStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttemptCallbackServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-18T06:00:00Z");
    private static final UUID ATTEMPT_ID = UUID.fromString("20000000-0000-4000-8000-000000000001");
    private static final UUID CALLBACK_KEY = UUID.fromString("b0000000-0000-4000-8000-000000000001");

    @Mock
    private CallbackTransactionService transactionService;

    @Mock
    private CallbackPayloadHasher payloadHasher;

    @Test
    void concurrentUniqueKeyLoserReloadsTheWinningReceipt() {
        AttemptCallbackRequest request = request(ATTEMPT_ID);
        CallbackReceiptResponse duplicate = new CallbackReceiptResponse(
                ATTEMPT_ID,
                CALLBACK_KEY,
                CallbackDisposition.DUPLICATE,
                CallbackDisposition.ACCEPTED,
                UUID.randomUUID(),
                CallbackStatus.PASSED,
                null,
                NOW
        );
        when(payloadHasher.hash(request)).thenReturn("a".repeat(64));
        when(transactionService.process(request, "a".repeat(64), NOW))
                .thenThrow(new DataIntegrityViolationException("duplicate"));
        when(transactionService.replayAfterConcurrentInsert(
                ATTEMPT_ID, CALLBACK_KEY, "a".repeat(64)
        )).thenReturn(Optional.of(duplicate));

        var service = new AttemptCallbackService(transactionService, payloadHasher);

        assertThat(service.callback(ATTEMPT_ID, request, NOW)).isEqualTo(duplicate);
        verify(transactionService).replayAfterConcurrentInsert(
                ATTEMPT_ID, CALLBACK_KEY, "a".repeat(64)
        );
    }

    @Test
    void rejectsPathAndPayloadAttemptMismatchBeforeHashing() {
        AttemptCallbackRequest request = request(ATTEMPT_ID);
        var service = new AttemptCallbackService(transactionService, payloadHasher);

        assertThatThrownBy(() -> service.callback(UUID.randomUUID(), request, NOW))
                .isInstanceOf(CallbackValidationException.class);

        verify(payloadHasher, org.mockito.Mockito.never()).hash(request);
        verify(transactionService, org.mockito.Mockito.never())
                .process(org.mockito.ArgumentMatchers.any(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    private AttemptCallbackRequest request(UUID attemptId) {
        return new AttemptCallbackRequest(
                "1.0.0", CALLBACK_KEY, attemptId, UUID.randomUUID(), "worker-1",
                CallbackStatus.PASSED, NOW, 12L, "ok", null, List.of(), Map.of()
        );
    }
}
