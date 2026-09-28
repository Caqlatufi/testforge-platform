package io.testforge.dispatcher.reliability.retry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryFailureClassifierTest {

    private final RetryFailureClassifier classifier = new RetryFailureClassifier();

    @Test
    void shouldNeverRetryAssertionAndScriptFailures() {
        assertThat(classifier.classify("ASSERTION_FAILED").isRetryable()).isFalse();
        assertThat(classifier.classify("product-defect").isRetryable()).isFalse();
        assertThat(classifier.classify("SCRIPT_ERROR").isRetryable()).isFalse();
        assertThat(classifier.classify("IMAGE_MATCH_TIMEOUT").isRetryable()).isFalse();
    }

    @Test
    void shouldRetryOnlyKnownInfrastructureFailures() {
        assertThat(classifier.classify("INFRA_FAILED")).isEqualTo(RetryFailureType.INFRA_FAILED);
        assertThat(classifier.classify("environment error")).isEqualTo(RetryFailureType.ENVIRONMENT);
        assertThat(classifier.classify("network-error")).isEqualTo(RetryFailureType.NETWORK);
        assertThat(classifier.classify("WORKER_LOST")).isEqualTo(RetryFailureType.WORKER_LOST);
        assertThat(classifier.classify("DEVICE_ERROR")).isEqualTo(RetryFailureType.DEVICE_ERROR);
        assertThat(classifier.classify("INFRASTRUCTURE").isRetryable()).isTrue();
    }

    @Test
    void shouldFailClosedForMissingOrFutureFailureTypes() {
        assertThat(classifier.classify(null)).isEqualTo(RetryFailureType.UNKNOWN);
        assertThat(classifier.classify(" ")).isEqualTo(RetryFailureType.UNKNOWN);
        assertThat(classifier.classify("MODEL_GUESSED_FAILURE"))
                .isEqualTo(RetryFailureType.UNKNOWN);
        assertThat(classifier.classify("MODEL_GUESSED_FAILURE").isRetryable()).isFalse();
    }
}
