package io.testforge.dispatcher.reliability;

import org.springframework.scheduling.annotation.Scheduled;

import java.util.Objects;

public final class ReliabilityReaperScheduler {

    private final AttemptRecoveryService recoveryService;

    public ReliabilityReaperScheduler(AttemptRecoveryService recoveryService) {
        this.recoveryService = Objects.requireNonNull(recoveryService, "recoveryService 不能为空");
    }

    @Scheduled(
            fixedDelayString = "${testforge.dispatcher.reliability.scan-delay:PT1S}",
            initialDelayString = "${testforge.dispatcher.reliability.initial-delay:PT1S}"
    )
    public void recover() {
        recoveryService.recoverOnce();
    }
}
