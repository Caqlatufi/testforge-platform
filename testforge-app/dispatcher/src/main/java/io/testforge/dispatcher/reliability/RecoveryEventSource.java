package io.testforge.dispatcher.reliability;

import io.testforge.dispatcher.reliability.retry.RetryEvent;

import java.time.Instant;
import java.util.List;

/** LOST 补偿和硬超时扫描来源；状态真相仍由 run-orchestrator/MySQL 提供。 */
public interface RecoveryEventSource {
    List<RetryEvent> findLost(int limit);

    List<RetryEvent> findTimedOut(Instant now, int limit);
}
