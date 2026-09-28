package io.testforge.workergateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.report.port.inbound.TestResultCommandPort;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import io.testforge.workergateway.artifact.service.ArtifactUploadPolicyService;
import io.testforge.workergateway.callback.ctrl.AttemptCallbackExceptionHandler;
import io.testforge.workergateway.callback.ctrl.AttemptController;
import io.testforge.workergateway.callback.entity.CallbackReceiptEntity;
import io.testforge.workergateway.callback.repo.CallbackReceiptRepository;
import io.testforge.workergateway.callback.service.AttemptCallbackService;
import io.testforge.workergateway.callback.service.AttemptExecutionGateway;
import io.testforge.workergateway.callback.service.AttemptLifecycleService;
import io.testforge.workergateway.callback.service.CallbackPayloadHasher;
import io.testforge.workergateway.callback.service.CallbackTransactionService;
import io.testforge.workergateway.callback.service.OrchestratorAttemptExecutionGateway;
import io.testforge.workergateway.callback.service.TestResultPublisher;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Clock;
import java.time.Duration;

/**
 * Attempt callback 子域的独立装配单元。
 *
 * <p>TFP-012 只需将该配置与 registry 配置一起导入 worker-gateway 聚合配置，
 * callback 子域本身不依赖 Worker 注册实现。</p>
 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = CallbackReceiptEntity.class)
@EnableJpaRepositories(basePackageClasses = CallbackReceiptRepository.class)
public class AttemptCallbackConfig {

    @Bean
    AttemptExecutionGateway attemptExecutionGateway(
            ObjectProvider<ExecutionReliabilityService> reliabilityServiceProvider
    ) {
        return new OrchestratorAttemptExecutionGateway(reliabilityServiceProvider);
    }

    @Bean
    TestResultPublisher testResultPublisher(
            ObjectProvider<TestResultCommandPort> resultPortProvider
    ) {
        return new TestResultPublisher(resultPortProvider);
    }

    @Bean
    CallbackPayloadHasher callbackPayloadHasher(ObjectMapper objectMapper) {
        return new CallbackPayloadHasher(objectMapper);
    }

    @Bean
    CallbackTransactionService callbackTransactionService(
            CallbackReceiptRepository receiptRepository,
            AttemptExecutionGateway executionGateway,
            TestResultPublisher resultPublisher,
            DeviceLeaseService deviceLeaseService,
            @Value("${testforge.worker-gateway.attempt-lease-duration:30s}") Duration leaseDuration
    ) {
        return new CallbackTransactionService(
                receiptRepository, executionGateway, resultPublisher, leaseDuration, deviceLeaseService
        );
    }

    @Bean
    AttemptCallbackService attemptCallbackService(
            CallbackTransactionService transactionService,
            CallbackPayloadHasher payloadHasher
    ) {
        return new AttemptCallbackService(transactionService, payloadHasher);
    }

    @Bean
    AttemptLifecycleService attemptLifecycleService(
            AttemptExecutionGateway executionGateway,
            DeviceLeaseService deviceLeaseService,
            ObjectProvider<Clock> clockProvider,
            @Value("${testforge.worker-gateway.attempt-lease-duration:30s}") Duration leaseDuration
    ) {
        return new AttemptLifecycleService(
                executionGateway,
                clockProvider.getIfAvailable(Clock::systemUTC),
                leaseDuration,
                deviceLeaseService
        );
    }

    @Bean
    AttemptController attemptController(
            AttemptLifecycleService lifecycleService,
            AttemptCallbackService callbackService,
            ObjectProvider<ArtifactUploadPolicyService> artifactUploadPolicyServiceProvider
    ) {
        return new AttemptController(
                lifecycleService, callbackService, artifactUploadPolicyServiceProvider.getIfAvailable()
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "testforge.artifact.oss", name = "enabled", havingValue = "true")
    ArtifactUploadPolicyService artifactUploadPolicyService(
            AttemptLifecycleService lifecycleService,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clockProvider,
            @Value("${testforge.artifact.oss.access-key-id}") String accessKeyId,
            @Value("${testforge.artifact.oss.access-key-secret}") String accessKeySecret,
            @Value("${testforge.artifact.oss.endpoint}") String endpoint,
            @Value("${testforge.artifact.oss.bucket}") String bucket,
            @Value("${testforge.artifact.oss.prefix:}") String prefix,
            @Value("${testforge.artifact.oss.policy-duration:5m}") Duration policyDuration,
            @Value("${testforge.artifact.oss.max-object-size:104857600}") long maxObjectSize
    ) {
        return new ArtifactUploadPolicyService(
                lifecycleService,
                objectMapper,
                accessKeyId,
                accessKeySecret,
                endpoint,
                bucket,
                prefix,
                policyDuration,
                maxObjectSize,
                clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }

    @Bean
    AttemptCallbackExceptionHandler attemptCallbackExceptionHandler() {
        return new AttemptCallbackExceptionHandler();
    }
}
