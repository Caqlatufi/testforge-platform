package io.testforge.dispatcher.port.outbound;

import java.time.Instant;
import java.util.UUID;

/**
 * 不携带任务参数与密钥引用的派发观测事件。
 */
public record DispatchObservation(
        UUID messageId,
        UUID taskId,
        String stream,
        int deliveryAttempt,
        DispatchOutcome outcome,
        boolean connectionRecovered,
        Instant observedAt,
        String detail
) {
}
