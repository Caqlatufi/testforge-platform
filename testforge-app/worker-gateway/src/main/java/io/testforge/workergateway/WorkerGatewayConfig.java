package io.testforge.workergateway;

import io.testforge.workergateway.adapter.inbound.WorkerGatewayInboundAdapterMarker;
import io.testforge.workergateway.device.registry.ctrl.DeviceRegistryController;
import io.testforge.workergateway.device.registry.entity.DeviceSlotEntity;
import io.testforge.workergateway.device.registry.repo.DeviceSlotRepository;
import io.testforge.workergateway.device.registry.service.DeviceRegistryService;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.registry.ctrl.WorkerRegistryController;
import io.testforge.workergateway.registry.entity.WorkerNodeEntity;
import io.testforge.workergateway.registry.repo.WorkerNodeRepository;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ComponentScan(basePackageClasses = {
        WorkerGatewayInboundAdapterMarker.class,
        WorkerRegistryController.class,
        DeviceRegistryController.class
})
@EntityScan(basePackageClasses = {WorkerNodeEntity.class, DeviceSlotEntity.class})
@EnableJpaRepositories(basePackageClasses = {WorkerNodeRepository.class, DeviceSlotRepository.class})
@Import({DeviceLeaseConfig.class, AttemptCallbackConfig.class})
public class WorkerGatewayConfig {

    @Bean
    WorkerRegistryService workerRegistryService(
            WorkerNodeRepository repository,
            @Value("${testforge.worker-gateway.registry.offline-timeout:15s}") Duration offlineTimeout
    ) {
        return new WorkerRegistryService(repository, Clock.systemUTC(), offlineTimeout);
    }

    @Bean
    DeviceRegistryService deviceRegistryService(
            DeviceSlotRepository repository,
            WorkerRegistryService workerRegistryService,
            ObjectProvider<DeviceLeaseService> deviceLeaseServiceProvider,
            @Value("${testforge.worker-gateway.device-registry.offline-timeout:15s}") Duration offlineTimeout
    ) {
        return new DeviceRegistryService(
                repository,
                workerRegistryService,
                Clock.systemUTC(),
                offlineTimeout,
                deviceLeaseServiceProvider.getIfAvailable()
        );
    }
}
