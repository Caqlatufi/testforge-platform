package io.testforge.runorchestrator.run.service;

import org.springframework.scheduling.annotation.Scheduled;

public class RunDeploymentReconciliationScheduler {
    private final RunTaskService runTaskService;

    public RunDeploymentReconciliationScheduler(RunTaskService runTaskService) {
        this.runTaskService = runTaskService;
    }

    @Scheduled(
            fixedDelayString = "${testforge.run.deployment-reconciliation-delay:PT30S}",
            initialDelayString = "${testforge.run.deployment-reconciliation-initial-delay:PT5S}"
    )
    public void reconcile() {
        runTaskService.failOrphanedActiveDeployments();
    }
}
