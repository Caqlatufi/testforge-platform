package io.testforge.workergateway;

import io.testforge.workergateway.device.lease.DeviceLeasePolicy;
import io.testforge.workergateway.device.lease.DeviceLeaseReaperScheduler;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.device.lease.DeviceSlotLeaseEntity;
import io.testforge.workergateway.device.lease.DeviceSlotLeaseRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

/** DeviceSlot 独占租约子域的装配入口。 */
@Configuration(proxyBeanMethods = false)
@EntityScan(basePackageClasses = DeviceSlotLeaseEntity.class)
@EnableJpaRepositories(basePackageClasses = DeviceSlotLeaseRepository.class)
@EnableScheduling
public class DeviceLeaseConfig {

    @Bean
    DeviceLeasePolicy deviceLeasePolicy(
            @Value("${testforge.worker-gateway.device-lease.lease-duration:15s}") Duration leaseDuration,
            @Value("${testforge.worker-gateway.device-lease.reaper-batch-size:100}") int reaperBatchSize
    ) {
        return new DeviceLeasePolicy(leaseDuration, reaperBatchSize);
    }

    @Bean
    DeviceLeaseService deviceLeaseService(
            DeviceSlotLeaseRepository repository,
            DeviceLeasePolicy policy,
            ObjectProvider<Clock> clockProvider
    ) {
        return new DeviceLeaseService(
                repository,
                policy,
                clockProvider.getIfAvailable(Clock::systemUTC),
                UUID::randomUUID
        );
    }

    @Bean
    @ConditionalOnProperty(
            prefix = "testforge.worker-gateway.device-lease.reaper",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true
    )
    DeviceLeaseReaperScheduler deviceLeaseReaperScheduler(DeviceLeaseService service) {
        return new DeviceLeaseReaperScheduler(service);
    }
}
