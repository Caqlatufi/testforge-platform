package io.testforge.dispatcher;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.dispatcher.adapter.inbound.DispatcherInboundAdapterMarker;
import io.testforge.dispatcher.adapter.outbound.JpaAttemptLeaseStore;
import io.testforge.dispatcher.adapter.outbound.OrchestratorRecoveryEventSource;
import io.testforge.dispatcher.adapter.outbound.OrchestratorRetryStateStore;
import io.testforge.dispatcher.outbox.entity.OutboxEventEntity;
import io.testforge.dispatcher.outbox.repo.OutboxEventRepository;
import io.testforge.dispatcher.outbox.service.OutboxClaimService;
import io.testforge.dispatcher.outbox.service.OutboxRegistrationService;
import io.testforge.dispatcher.port.outbound.DispatchObservationPort;
import io.testforge.dispatcher.port.outbound.OutboxMessagePublisher;
import io.testforge.dispatcher.relay.OutboxRelay;
import io.testforge.dispatcher.relay.OutboxRelayScheduler;
import io.testforge.dispatcher.relay.RelayRetryPolicy;
import io.testforge.dispatcher.reliability.AttemptRecoveryService;
import io.testforge.dispatcher.reliability.RecoveryEventSource;
import io.testforge.dispatcher.reliability.ReliabilityReaperScheduler;
import io.testforge.dispatcher.reliability.lease.AttemptLeaseService;
import io.testforge.dispatcher.reliability.lease.AttemptLeaseStore;
import io.testforge.dispatcher.reliability.lease.LeasePolicy;
import io.testforge.dispatcher.reliability.lease.LeaseReaper;
import io.testforge.dispatcher.reliability.retry.RetryCoordinator;
import io.testforge.dispatcher.reliability.retry.RetryPolicy;
import io.testforge.dispatcher.reliability.retry.RetryStateStore;
import io.testforge.dispatcher.stream.DispatchTelemetry;
import io.testforge.dispatcher.stream.RedisDispatchAvailability;
import io.testforge.dispatcher.stream.RedisDispatchPolicy;
import io.testforge.dispatcher.stream.RedisOutboxMessagePublisher;
import io.testforge.dispatcher.stream.RedisStreamRouteResolver;
import io.testforge.dispatcher.stream.StringRedisStreamWriter;
import io.testforge.runorchestrator.service.reliability.ExecutionReliabilityService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = DispatcherInboundAdapterMarker.class)
@EntityScan(basePackageClasses = OutboxEventEntity.class)
@EnableJpaRepositories(basePackageClasses = OutboxEventRepository.class)
@EnableScheduling
public class DispatcherConfig {

    @Bean
    OutboxRegistrationService outboxRegistrationService(
            OutboxEventRepository repository,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clockProvider
    ) {
        return new OutboxRegistrationService(
                repository,
                objectMapper,
                clockProvider.getIfAvailable(Clock::systemUTC),
                UUID::randomUUID
        );
    }

    @Bean
    OutboxClaimService outboxClaimService(
            OutboxEventRepository repository,
            ObjectProvider<Clock> clockProvider
    ) {
        return new OutboxClaimService(
                repository,
                clockProvider.getIfAvailable(Clock::systemUTC),
                UUID::randomUUID
        );
    }

    @Bean
    RelayRetryPolicy relayRetryPolicy() {
        return new RelayRetryPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5));
    }

    @Bean
    OutboxRelay outboxRelay(
            OutboxClaimService claimService,
            ObjectProvider<OutboxMessagePublisher> publisherProvider,
            RelayRetryPolicy retryPolicy,
            ObjectProvider<Clock> clockProvider
    ) {
        return new OutboxRelay(
                claimService,
                publisherProvider::getIfAvailable,
                retryPolicy,
                clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }

    @Bean
    @ConditionalOnMissingBean(DispatchObservationPort.class)
    DispatchTelemetry dispatchTelemetry() {
        return new DispatchTelemetry();
    }

    @Bean
    RedisStreamRouteResolver redisStreamRouteResolver(
            @Value("${testforge.dispatcher.stream-prefix:testforge:tasks}") String streamPrefix
    ) {
        return new RedisStreamRouteResolver(streamPrefix);
    }

    @Bean
    RedisDispatchAvailability redisDispatchAvailability(
            @Value("${testforge.dispatcher.redis-pause-duration:5s}") Duration pauseDuration
    ) {
        return new RedisDispatchAvailability(new RedisDispatchPolicy(pauseDuration));
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "testforge.dispatcher.redis",
            name = "enabled",
            havingValue = "true"
    )
    OutboxMessagePublisher redisOutboxMessagePublisher(
            StringRedisTemplate redisTemplate,
            RedisStreamRouteResolver routeResolver,
            RedisDispatchAvailability availability,
            DispatchObservationPort observationPort,
            ObjectMapper objectMapper,
            ObjectProvider<Clock> clockProvider
    ) {
        return new RedisOutboxMessagePublisher(
                new StringRedisStreamWriter(redisTemplate),
                routeResolver,
                availability,
                observationPort,
                objectMapper,
                clockProvider.getIfAvailable(Clock::systemUTC)
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "testforge.dispatcher.relay",
            name = "enabled",
            havingValue = "true"
    )
    OutboxRelayScheduler outboxRelayScheduler(
            OutboxRelay relay,
            @Value("${testforge.dispatcher.relay.owner:dispatcher-relay}") String owner,
            @Value("${testforge.dispatcher.relay.batch-size:100}") int batchSize,
            @Value("${testforge.dispatcher.relay.lease-duration:30s}") Duration leaseDuration
    ) {
        return new OutboxRelayScheduler(relay, owner, batchSize, leaseDuration);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    LeasePolicy attemptLeasePolicy(
            @Value("${testforge.dispatcher.reliability.lease-duration:15s}") Duration leaseDuration,
            @Value("${testforge.dispatcher.reliability.batch-size:100}") int batchSize
    ) {
        return new LeasePolicy(LeasePolicy.REQUIRED_HEARTBEAT_INTERVAL, leaseDuration, batchSize);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    AttemptLeaseStore attemptLeaseStore(ExecutionReliabilityService reliabilityService) {
        return new JpaAttemptLeaseStore(reliabilityService);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    AttemptLeaseService attemptLeaseService(
            AttemptLeaseStore store,
            LeasePolicy policy,
            ObjectProvider<Clock> clockProvider
    ) {
        return new AttemptLeaseService(
                store,
                policy,
                clockProvider.getIfAvailable(Clock::systemUTC),
                UUID::randomUUID
        );
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    LeaseReaper leaseReaper(
            AttemptLeaseStore store,
            LeasePolicy policy,
            ObjectProvider<Clock> clockProvider
    ) {
        return new LeaseReaper(store, policy, clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    RetryPolicy attemptRetryPolicy(
            @Value("${testforge.dispatcher.reliability.max-attempts:3}") int maxAttempts,
            @Value("${testforge.dispatcher.reliability.initial-backoff:5s}") Duration initialBackoff,
            @Value("${testforge.dispatcher.reliability.max-backoff:1m}") Duration maxBackoff
    ) {
        return new RetryPolicy(maxAttempts, initialBackoff, maxBackoff);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    RetryStateStore retryStateStore(ExecutionReliabilityService reliabilityService) {
        return new OrchestratorRetryStateStore(reliabilityService);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    RetryCoordinator retryCoordinator(RetryStateStore store, RetryPolicy policy) {
        return new RetryCoordinator(store, policy);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    RecoveryEventSource recoveryEventSource(ExecutionReliabilityService reliabilityService) {
        return new OrchestratorRecoveryEventSource(reliabilityService);
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    AttemptRecoveryService attemptRecoveryService(
            LeaseReaper leaseReaper,
            RecoveryEventSource eventSource,
            RetryCoordinator retryCoordinator,
            ObjectProvider<Clock> clockProvider,
            @Value("${testforge.dispatcher.reliability.batch-size:100}") int batchSize
    ) {
        return new AttemptRecoveryService(
                leaseReaper,
                eventSource,
                retryCoordinator,
                clockProvider.getIfAvailable(Clock::systemUTC),
                batchSize
        );
    }

    @Bean
    @ConditionalOnBean(ExecutionReliabilityService.class)
    @ConditionalOnProperty(
            prefix = "testforge.dispatcher.reliability",
            name = "enabled",
            havingValue = "true"
    )
    ReliabilityReaperScheduler reliabilityReaperScheduler(AttemptRecoveryService recoveryService) {
        return new ReliabilityReaperScheduler(recoveryService);
    }
}
