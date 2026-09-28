package io.testforge.dispatcher.reliability.retry;

import java.util.UUID;

public final class RetryContentionException extends RuntimeException {

    public RetryContentionException(UUID taskId, int attempts) {
        super("重试状态竞争在 " + attempts + " 次 CAS 后仍未收敛: taskId=" + taskId);
    }
}
