package io.testforge.workergateway.device.registry.ctrl;

import io.testforge.workergateway.device.registry.model.DeviceRequirement;
import io.testforge.workergateway.device.registry.model.DeviceSlotStatus;
import io.testforge.workergateway.device.registry.model.DeviceSlotView;
import io.testforge.workergateway.device.registry.model.RegisterDeviceSlotCommand;
import io.testforge.workergateway.device.registry.service.DeviceRegistryService;
import io.testforge.workergateway.device.lease.DeviceLeaseService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class DeviceRegistryController {

    private final DeviceRegistryService service;
    private final DeviceLeaseService leases;

    public DeviceRegistryController(DeviceRegistryService service, DeviceLeaseService leases) {
        this.service = service;
        this.leases = leases;
    }

    @PutMapping("/workers/{workerId}/devices")
    public DeviceRegistryResponse<DeviceSlotView> register(
            @PathVariable String workerId,
            @Valid @RequestBody RegisterDeviceSlotRequest request
    ) {
        return DeviceRegistryResponse.success(service.register(new RegisterDeviceSlotCommand(
                workerId,
                request.deviceId(),
                request.platform(),
                request.deviceUri(),
                request.features(),
                request.resolution()
        )));
    }

    @PostMapping("/workers/{workerId}/devices/{deviceId}/heartbeat")
    public DeviceRegistryResponse<DeviceSlotView> heartbeat(
            @PathVariable String workerId,
            @PathVariable String deviceId
    ) {
        return DeviceRegistryResponse.success(service.heartbeat(workerId, deviceId));
    }

    @GetMapping("/workers/{workerId}/devices/{deviceId}")
    public DeviceRegistryResponse<DeviceSlotView> get(
            @PathVariable String workerId,
            @PathVariable String deviceId
    ) {
        return DeviceRegistryResponse.success(service.get(workerId, deviceId));
    }

    @GetMapping("/devices")
    public DeviceRegistryResponse<List<DeviceSlotView>> list(
            @RequestParam(required = false) DeviceSlotStatus status
    ) {
        return DeviceRegistryResponse.success(service.list(status));
    }

    @GetMapping("/device-slots")
    public DeviceRegistryResponse<List<DevicePanelView>> panel() {
        return DeviceRegistryResponse.success(service.list(null).stream().map(slot -> {
            var lease = leases.load(slot.slotId());
            return new DevicePanelView(
                    slot.slotId(), slot.workerId(), slot.deviceId(), slot.platform(), slot.features(),
                    slot.status().name(), lease.state().name(), lease.currentAttemptId(),
                    lease.leaseUntil(), slot.lastHeartbeatAt(), slot.expiresAt()
            );
        }).toList());
    }

    @GetMapping("/devices/match")
    public DeviceRegistryResponse<List<DeviceSlotView>> match(
            @RequestParam String platform,
            @RequestParam(required = false) Set<String> requiredFeatures
    ) {
        return DeviceRegistryResponse.success(service.findMatching(
                new DeviceRequirement(platform, requiredFeatures)
        ));
    }

    public record RegisterDeviceSlotRequest(
            @NotBlank @Size(max = 128) String deviceId,
            @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String platform,
            @NotBlank @Size(max = 512) String deviceUri,
            @Size(max = 64) Set<
                    @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{0,63}$") String> features,
            @Size(max = 64) String resolution
    ) {
    }

    public record DevicePanelView(
            UUID slotId,
            String workerId,
            String deviceId,
            String platform,
            Set<String> features,
            String status,
            String leaseState,
            UUID currentAttemptId,
            Instant leaseUntil,
            Instant lastHeartbeatAt,
            Instant expiresAt
    ) { }
}
