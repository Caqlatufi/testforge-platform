package io.testforge.dispatcher.reliability.retry;

import java.util.Locale;
import java.util.Map;

/** 将公开回调协议中的 failure type 收敛为调度器的重试分类。 */
public final class RetryFailureClassifier {

    private static final Map<String, RetryFailureType> ALIASES = Map.ofEntries(
            Map.entry("ASSERTION", RetryFailureType.ASSERTION_FAILED),
            Map.entry("ASSERTION_ERROR", RetryFailureType.ASSERTION_FAILED),
            Map.entry("INFRASTRUCTURE", RetryFailureType.INFRA_FAILED),
            Map.entry("INFRASTRUCTURE_ERROR", RetryFailureType.INFRA_FAILED),
            Map.entry("ENVIRONMENT_ERROR", RetryFailureType.ENVIRONMENT),
            Map.entry("NETWORK_ERROR", RetryFailureType.NETWORK),
            Map.entry("DEVICE_LOST", RetryFailureType.DEVICE_ERROR),
            Map.entry("WORKER_ERROR", RetryFailureType.WORKER_LOST)
    );

    public RetryFailureType classify(String rawFailureType) {
        if (rawFailureType == null || rawFailureType.isBlank()) {
            return RetryFailureType.UNKNOWN;
        }
        String normalized = rawFailureType.trim()
                .toUpperCase(Locale.ROOT)
                .replace('-', '_')
                .replace(' ', '_');
        RetryFailureType alias = ALIASES.get(normalized);
        if (alias != null) {
            return alias;
        }
        try {
            return RetryFailureType.valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return RetryFailureType.UNKNOWN;
        }
    }
}
