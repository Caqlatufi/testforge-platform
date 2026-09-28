package io.testforge.workergateway.device.registry;

import io.testforge.workergateway.WorkerGatewayConfig;
import io.testforge.workergateway.device.registry.model.DeviceRequirement;
import io.testforge.workergateway.device.registry.model.RegisterDeviceSlotCommand;
import io.testforge.workergateway.device.registry.repo.DeviceSlotRepository;
import io.testforge.workergateway.device.registry.service.DeviceRegistryService;
import io.testforge.workergateway.registry.model.RegisterWorkerCommand;
import io.testforge.workergateway.registry.repo.WorkerNodeRepository;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:device_registry;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "testforge.worker-gateway.registry.offline-timeout=15s",
        "testforge.worker-gateway.device-registry.offline-timeout=15s"
})
@AutoConfigureMockMvc
class DeviceRegistryPersistenceIntegrationTest {

    @Autowired
    private WorkerRegistryService workerRegistryService;

    @Autowired
    private DeviceRegistryService deviceRegistryService;

    @Autowired
    private WorkerNodeRepository workerNodeRepository;

    @Autowired
    private DeviceSlotRepository deviceSlotRepository;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        deviceSlotRepository.deleteAll();
        workerNodeRepository.deleteAll();
        workerRegistryService.register(new RegisterWorkerCommand(
                "worker-device-db-01",
                "1.0",
                Set.of("AIRTEST", "WINDOWS", "WINDOWS_UI"),
                1
        ));
    }

    @Test
    void persistsOneStableSlotWhenRegistrationIsRepeated() {
        var first = deviceRegistryService.register(command(Set.of("WINDOWS_UI", "AIRTEST")));
        var updated = deviceRegistryService.register(command(Set.of("WINDOWS_UI", "SCREENSHOT")));

        assertThat(updated.slotId()).isEqualTo(first.slotId());
        assertThat(updated.features()).containsExactlyInAnyOrder("WINDOWS_UI", "SCREENSHOT");
        assertThat(deviceSlotRepository.count()).isEqualTo(1);
        assertThat(deviceRegistryService.findMatching(new DeviceRequirement(
                "WINDOWS", Set.of("WINDOWS_UI", "SCREENSHOT")
        ))).extracting("slotId").containsExactly(first.slotId());
    }

    @Test
    void exposesRegistrationHeartbeatAndMatchingEndpoints() throws Exception {
        mockMvc.perform(put("/api/v1/workers/worker-device-db-01/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "deviceId": "window-sandbox",
                                  "platform": "WINDOWS",
                                  "deviceUri": "Windows:///Skill Sandbox",
                                  "features": ["WINDOWS_UI", "SCREENSHOT"],
                                  "resolution": "1280x720"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.deviceId").value("window-sandbox"))
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));

        mockMvc.perform(get("/api/v1/devices/match")
                        .queryParam("platform", "WINDOWS")
                        .queryParam("requiredFeatures", "WINDOWS_UI", "SCREENSHOT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].deviceId").value("window-sandbox"));

        mockMvc.perform(post(
                        "/api/v1/workers/worker-device-db-01/devices/window-sandbox/heartbeat"
                ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("AVAILABLE"));
    }

    private RegisterDeviceSlotCommand command(Set<String> features) {
        return new RegisterDeviceSlotCommand(
                "worker-device-db-01",
                "window-sandbox",
                "WINDOWS",
                "Windows:///Skill Sandbox",
                features,
                "1280x720"
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(WorkerGatewayConfig.class)
    static class TestApplication {
    }
}
