package io.testforge.runorchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.casecatalog.workflow.compile.service.WorkflowPublishService;
import io.testforge.casecatalog.workflow.service.TestWorkflowService;
import io.testforge.cicdgateway.deployment.service.DeploymentService;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import io.testforge.runorchestrator.entity.attempt.TaskAttemptEntity;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistrationPort;
import io.testforge.common.event.ExecutionEventPort;
import io.testforge.common.event.ExecutionEventQueryPort;
import io.testforge.runorchestrator.repo.attempt.TaskAttemptRepository;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.runorchestrator.run.entity.TestRunEntity;
import io.testforge.runorchestrator.comparison.entity.ComparisonRunEntity;
import io.testforge.runorchestrator.comparison.repo.ComparisonRunRepository;
import io.testforge.runorchestrator.comparison.service.ComparisonRunService;
import io.testforge.runorchestrator.run.repo.TestRunRepository;
import io.testforge.runorchestrator.run.service.RunTaskService;
import io.testforge.runorchestrator.run.service.RunEventStreamService;
import io.testforge.runorchestrator.run.service.RunDeploymentReconciliationScheduler;
import io.testforge.runorchestrator.service.attempt.AttemptTransitionService;
import io.testforge.runorchestrator.service.quota.RunConcurrencyQuotaService;
import io.testforge.runorchestrator.service.quota.RunQuotaRepository;
import io.testforge.runorchestrator.service.quota.TaskQuotaRepository;
import io.testforge.runorchestrator.service.scheduling.RunSchedulingService;
import io.testforge.runorchestrator.service.scheduling.SchedulingCandidateRepository;
import io.testforge.runorchestrator.task.dag.release.DagReleaseService;
import io.testforge.runorchestrator.task.dag.release.DagReleaseStateStore;
import io.testforge.runorchestrator.task.dag.release.JpaDagReleaseStateStore;
import io.testforge.runorchestrator.task.entity.TaskDependencyEntity;
import io.testforge.runorchestrator.task.entity.TestTaskEntity;
import io.testforge.runorchestrator.task.repo.TaskDependencyRepository;
import io.testforge.runorchestrator.task.repo.TestTaskRepository;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackages = {"io.testforge.runorchestrator.ctrl", "io.testforge.runorchestrator.job"})
@EntityScan(basePackageClasses = {
        TestRunEntity.class,
        ComparisonRunEntity.class,
        TestTaskEntity.class,
        TaskDependencyEntity.class,
        TaskAttemptEntity.class
})
@EnableJpaRepositories(basePackageClasses = {
        TestRunRepository.class,
        ComparisonRunRepository.class,
        TestTaskRepository.class,
        TaskDependencyRepository.class,
        TaskAttemptRepository.class,
        RunQuotaRepository.class,
        TaskQuotaRepository.class,
        SchedulingCandidateRepository.class
})
public class RunOrchestratorConfig {

    @Bean
    ComparisonRunService comparisonRunService(
            ComparisonRunRepository repository,
            RunTaskService runTaskService,
            ObjectMapper objectMapper
    ) {
        return new ComparisonRunService(repository, runTaskService, objectMapper);
    }

    @Bean
    RunTaskService runTaskService(
            TestRunRepository runRepository,
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository,
            TaskAttemptRepository attemptRepository,
            ProjectCatalogService projectCatalogService,
            WorkflowPublishService workflowPublishService,
            TestWorkflowService workflowCatalogService,
            ObjectMapper objectMapper,
            ObjectProvider<TaskDispatchRegistrationPort> dispatchRegistrationPortProvider,
            ObjectProvider<ExecutionEventPort> executionEventPortProvider,
            ObjectProvider<DeploymentService> deploymentServiceProvider
    ) {
        return new RunTaskService(
                runRepository,
                taskRepository,
                dependencyRepository,
                attemptRepository,
                projectCatalogService,
                workflowPublishService,
                workflowCatalogService,
                objectMapper,
                new io.testforge.runorchestrator.run.service.RunAggregationPolicy(),
                java.time.Clock.systemUTC(),
                java.util.UUID::randomUUID,
                dispatchRegistrationPortProvider.getIfAvailable(TaskDispatchRegistrationPort::noop),
                executionEventPortProvider.getIfAvailable(ExecutionEventPort::noop),
                deploymentServiceProvider.getIfAvailable()
        );
    }

    @Bean
    RunDeploymentReconciliationScheduler runDeploymentReconciliationScheduler(
            RunTaskService runTaskService
    ) {
        return new RunDeploymentReconciliationScheduler(runTaskService);
    }

    @Bean
    RunEventStreamService runEventStreamService(
            RunTaskService runTaskService,
            ObjectProvider<ExecutionEventQueryPort> eventQueryPortProvider
    ) {
        return new RunEventStreamService(
                runTaskService,
                eventQueryPortProvider.getIfAvailable(ExecutionEventQueryPort::noop)
        );
    }

    @Bean
    AttemptTransitionService attemptTransitionService(TaskAttemptRepository repository) {
        return new AttemptTransitionService(repository);
    }

    @Bean
    ExecutionReliabilityService executionReliabilityService(
            TestTaskRepository taskRepository,
            TaskAttemptRepository attemptRepository,
            TestRunRepository runRepository,
            ObjectProvider<TaskDispatchRegistrationPort> dispatchRegistrationPortProvider,
            ObjectMapper objectMapper,
            RunConcurrencyQuotaService quotaService,
            ObjectProvider<ExecutionEventPort> executionEventPortProvider
    ) {
        return new ExecutionReliabilityService(
                taskRepository,
                attemptRepository,
                runRepository,
                dispatchRegistrationPortProvider.getIfAvailable(TaskDispatchRegistrationPort::noop),
                objectMapper,
                quotaService,
                executionEventPortProvider.getIfAvailable(ExecutionEventPort::noop)
        );
    }

    @Bean
    DagReleaseStateStore dagReleaseStateStore(
            TestTaskRepository taskRepository,
            TaskDependencyRepository dependencyRepository
    ) {
        return new JpaDagReleaseStateStore(taskRepository, dependencyRepository);
    }

    @Bean
    DagReleaseService dagReleaseService(DagReleaseStateStore stateStore) {
        return new DagReleaseService(stateStore);
    }

    @Bean
    RunConcurrencyQuotaService runConcurrencyQuotaService(
            RunQuotaRepository runRepository,
            TaskQuotaRepository taskRepository,
            DagReleaseService dagReleaseService,
            ObjectProvider<TaskDispatchRegistrationPort> dispatchRegistrationPortProvider,
            ObjectMapper objectMapper
    ) {
        return new RunConcurrencyQuotaService(
                runRepository,
                taskRepository,
                dagReleaseService,
                dispatchRegistrationPortProvider.getIfAvailable(TaskDispatchRegistrationPort::noop),
                objectMapper
        );
    }

    @Bean
    RunSchedulingService runSchedulingService(
            SchedulingCandidateRepository candidateRepository
    ) {
        return new RunSchedulingService(candidateRepository);
    }
}
