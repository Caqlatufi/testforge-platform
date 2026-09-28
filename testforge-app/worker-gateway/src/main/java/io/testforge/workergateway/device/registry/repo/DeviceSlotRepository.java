package io.testforge.workergateway.device.registry.repo;

import io.testforge.workergateway.device.registry.entity.DeviceSlotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeviceSlotRepository extends JpaRepository<DeviceSlotEntity, UUID> {

    Optional<DeviceSlotEntity> findByWorkerIdAndDeviceId(String workerId, String deviceId);

    List<DeviceSlotEntity> findAllByOrderByWorkerIdAscDeviceIdAsc();
}
