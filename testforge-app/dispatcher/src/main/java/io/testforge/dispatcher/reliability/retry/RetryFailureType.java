package io.testforge.dispatcher.reliability.retry;

/**
 * Worker 结果进入调度器后的确定性失败分类。
 *
 * <p>只有环境、网络、Worker/设备丢失等基础设施故障默认允许自动重试；
 * 断言、产品缺陷、脚本和图片匹配失败均需要保留原结论，避免用重试掩盖问题。</p>
 */
public enum RetryFailureType {
    ASSERTION_FAILED(false),
    PRODUCT_DEFECT(false),
    SCRIPT_ERROR(false),
    IMAGE_MATCH_TIMEOUT(false),
    INFRA_FAILED(true),
    ENVIRONMENT(true),
    NETWORK(true),
    WORKER_LOST(true),
    DEVICE_ERROR(true),
    TIMEOUT(true),
    CANCELLED(false),
    UNKNOWN(false);

    private final boolean retryable;

    RetryFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
