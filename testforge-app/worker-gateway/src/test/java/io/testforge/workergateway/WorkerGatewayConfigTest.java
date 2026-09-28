package io.testforge.workergateway;

import io.testforge.workergateway.adapter.inbound.WorkerGatewayInboundAdapterMarker;
import io.testforge.workergateway.device.registry.ctrl.DeviceRegistryController;
import io.testforge.workergateway.registry.ctrl.WorkerRegistryController;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class WorkerGatewayConfigTest {

    @Test
    void declaresModuleConfiguration() {
        assertNotNull(WorkerGatewayConfig.class.getAnnotation(Configuration.class));
    }

    @Test
    void scansOnlyFromTheWorkerGatewayModuleRoot() {
        var componentScan = WorkerGatewayConfig.class.getAnnotation(ComponentScan.class);

        assertNotNull(componentScan);
        assertArrayEquals(
                new Class<?>[]{
                        WorkerGatewayInboundAdapterMarker.class,
                        WorkerRegistryController.class,
                        DeviceRegistryController.class
                },
                componentScan.basePackageClasses()
        );
    }
}
