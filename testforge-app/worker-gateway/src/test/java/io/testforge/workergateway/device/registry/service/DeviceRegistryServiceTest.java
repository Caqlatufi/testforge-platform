package io.testforge.workergateway.device.registry.service;

import io.testforge.workergateway.device.registry.entity.DeviceSlotEntity;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import io.testforge.workergateway.device.lease.DeviceLeaseSnapshot;
import io.testforge.workergateway.device.lease.DeviceLeaseState;
import io.testforge.workergateway.device.registry.model.DeviceRequirement;
import io.testforge.workergateway.device.registry.model.DeviceSlotStatus;
import io.testforge.workergateway.device.registry.model.RegisterDeviceSlotCommand;
import io.testforge.workergateway.device.registry.repo.DeviceSlotRepository;
import io.testforge.workergateway.registry.model.WorkerNodeView;
import io.testforge.workergateway.registry.model.WorkerStatus;
import io.testforge.workergateway.registry.service.WorkerRegistryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeviceRegistryServiceTest {

    private static final Instant START = Instant.parse("2026-09-18T08:00:00Z");

    private final Map<String, DeviceSlotEntity> slots = new LinkedHashMap<>();
    private final Map<String, WorkerStatus> workerStatuses = new LinkedHashMap<>();
    private final DeviceSlotRepository repository = mock(DeviceSlotRepository.class);
    private final WorkerRegistryService workerRegistryService = mock(WorkerRegistryService.class);
    private final MutableClock clock = new MutableClock(START);
    private DeviceRegistryService service;

    @BeforeEach
    void setUp() {
        slots.clear();
        workerStatuses.clear();
        when(repository.findByWorkerIdAndDeviceId(anyString(), anyString())).thenAnswer(invocation ->
                Optional.ofNullable(slots.get(key(invocation.getArgument(0), invocation.getArgument(1))))
        );
        when(repository.findAllByOrderByWorkerIdAscDeviceIdAsc()).thenAnswer(invocation ->
                slots.values().stream()
                        .sorted((left, right) -> key(left.getWorkerId(), left.getDeviceId())
                                .compareTo(key(right.getWorkerId(), right.getDeviceId())))
                        .toList()
        );
        when(repository.saveAndFlush(any(DeviceSlotEntity.class))).thenAnswer(invocation -> {
            DeviceSlotEntity slot = invocation.getArgument(0);
            slots.put(key(slot.getWorkerId(), slot.getDeviceId()), slot);
            return slot;
        });
        when(workerRegistryService.get(anyString())).thenAnswer(invocation -> {
            String workerId = invocation.getArgument(0);
            WorkerStatus status = workerStatuses.getOrDefault(workerId, WorkerStatus.ONLINE);
            return workerView(workerId, status);
        });
        service = new DeviceRegistryService(
                repository,
                workerRegistryService,
                clock,
                Duration.ofSeconds(15)
        );
    }

    @Test
    void repeatedRegistrationReusesStableSlotAndReplacesCapabilities() {
        var first = service.register(command(
                "worker-win-01",
                "window-sandbox",
                "WINDOWS",
                "Windows:///Skill Sandbox",
                Set.of("WINDOWS_UI", "AIRTEST"),
                "1280x720"
        ));

        clock.advance(Duration.ofSeconds(3));
        var updated = service.register(command(
                "worker-win-01",
                "window-sandbox",
                "WINDOWS",
                "Windows:///Skill Sandbox v2",
                Set.of("WINDOWS_UI", "SCREENSHOT"),
                "1920x1080"
        ));

        assertThat(updated.slotId()).isEqualTo(first.slotId());
        assertThat(updated.registeredAt()).isEqualTo(first.registeredAt());
        assertThat(updated.lastHeartbeatAt()).isEqualTo(START.plusSeconds(3));
        assertThat(updated.deviceUri()).isEqualTo("Windows:///Skill Sandbox v2");
        assertThat(updated.features()).containsExactlyInAnyOrder("WINDOWS_UI", "SCREENSHOT");
        assertThat(updated.resolution()).isEqualTo("1920x1080");
        assertThat(slots).hasSize(1);
    }

    @Test
    void heartbeatRenewsDeadlineAndExactExpiryIsOffline() {
        service.register(command(
                "worker-win-01", "window-sandbox", "WINDOWS", "Windows:///Skill Sandbox",
                Set.of("WINDOWS_UI"), "1280x720"
        ));
        clock.advance(Duration.ofSeconds(14));

        var heartbeat = service.heartbeat("worker-win-01", "window-sandbox");
        clock.advance(Duration.ofSeconds(15));

        assertThat(heartbeat.expiresAt()).isEqualTo(START.plusSeconds(29));
        assertThat(service.get("worker-win-01", "window-sandbox").status())
                .isEqualTo(DeviceSlotStatus.OFFLINE);
        assertThat(service.list(DeviceSlotStatus.AVAILABLE)).isEmpty();
        assertThat(service.list(DeviceSlotStatus.OFFLINE)).extracting("deviceId")
                .containsExactly("window-sandbox");
    }

    @Test
    void matchingFiltersOfflineAndIncapableSlotsAndUsesDeterministicScarcityOrder() {
        register("worker-a", "exact-old", Set.of("WINDOWS_UI"));
        register("worker-a", "device-offline", Set.of("WINDOWS_UI"));
        clock.advance(Duration.ofSeconds(5));
        register("worker-b", "exact-fresh", Set.of("WINDOWS_UI"));
        clock.advance(Duration.ofSeconds(5));
        register("worker-c", "rich", Set.of("WINDOWS_UI", "CAMERA"));
        register("worker-d", "wrong-platform", "ANDROID", Set.of("WINDOWS_UI"));
        clock.advance(Duration.ofSeconds(4));
        service.heartbeat("worker-a", "exact-old");
        clock.advance(Duration.ofSeconds(1));
        service.heartbeat("worker-b", "exact-fresh");
        clock.advance(Duration.ofSeconds(1));
        service.heartbeat("worker-c", "rich");
        register("worker-z", "worker-offline", Set.of("WINDOWS_UI"));
        workerStatuses.put("worker-z", WorkerStatus.OFFLINE);

        var matches = service.findMatching(new DeviceRequirement(
                "windows", Set.of("windows-ui")
        ));

        assertThat(matches).extracting("deviceId")
                .containsExactly("exact-fresh", "exact-old", "rich");
        assertThat(matches).allMatch(slot -> slot.status() == DeviceSlotStatus.AVAILABLE);
    }

    @Test
    void registrationRequiresAnOnlineWorker() {
        workerStatuses.put("worker-offline", WorkerStatus.OFFLINE);

        assertThatThrownBy(() -> register(
                "worker-offline", "window-sandbox", Set.of("WINDOWS_UI")
        ))
                .isInstanceOf(DeviceRegistryValidationException.class)
                .hasMessageContaining("Worker 当前不在线");
    }

    @Test
    void reservedLeaseIsVisibleAndExcludedFromMatching() {
        DeviceLeaseService leaseService = mock(DeviceLeaseService.class);
        when(leaseService.load(any(UUID.class))).thenAnswer(invocation -> {
            UUID slotId = invocation.getArgument(0);
            return new DeviceLeaseSnapshot(
                    slotId,
                    DeviceLeaseState.AVAILABLE,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    0
            );
        });
        DeviceRegistryService leasedService = new DeviceRegistryService(
                repository,
                workerRegistryService,
                clock,
                Duration.ofSeconds(15),
                leaseService
        );
        var registered = leasedService.register(command(
                "worker-win-01", "window-sandbox", "WINDOWS", "Windows:///Skill Sandbox",
                Set.of("WINDOWS_UI"), "1280x720"
        ));
        UUID attemptId = UUID.randomUUID();
        when(leaseService.load(registered.slotId())).thenReturn(new DeviceLeaseSnapshot(
                registered.slotId(),
                DeviceLeaseState.RESERVED,
                attemptId,
                UUID.randomUUID(),
                clock.instant().plusSeconds(30),
                clock.instant(),
                clock.instant(),
                null,
                null,
                1
        ));

        assertThat(leasedService.get("worker-win-01", "window-sandbox").status())
                .isEqualTo(DeviceSlotStatus.RESERVED);
        assertThat(leasedService.list(DeviceSlotStatus.RESERVED))
                .extracting("deviceId")
                .containsExactly("window-sandbox");
        assertThat(leasedService.findMatching(new DeviceRequirement(
                "WINDOWS", Set.of("WINDOWS_UI")
        ))).isEmpty();
    }

    private void register(String workerId, String deviceId, Set<String> features) {
        register(workerId, deviceId, "WINDOWS", features);
    }

    private void register(String workerId, String deviceId, String platform, Set<String> features) {
        service.register(command(
                workerId,
                deviceId,
                platform,
                platform + "://" + deviceId,
                features,
                "1280x720"
        ));
    }

    private RegisterDeviceSlotCommand command(
            String workerId,
            String deviceId,
            String platform,
            String deviceUri,
            Set<String> features,
            String resolution
    ) {
        return new RegisterDeviceSlotCommand(
                workerId, deviceId, platform, deviceUri, features, resolution
        );
    }

    private WorkerNodeView workerView(String workerId, WorkerStatus status) {
        return new WorkerNodeView(
                UUID.nameUUIDFromBytes(workerId.getBytes()),
                workerId,
                "1.0",
                Set.of("AIRTEST", "WINDOWS", "WINDOWS_UI"),
                1,
                status,
                START,
                clock.instant(),
                clock.instant().plusSeconds(15),
                clock.instant(),
                0
        );
    }

    private static String key(String workerId, String deviceId) {
        return workerId + "\u0000" + deviceId;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
