package io.testforge.workergateway.callback.repo;

import io.testforge.workergateway.callback.entity.CallbackReceiptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CallbackReceiptRepository extends JpaRepository<CallbackReceiptEntity, UUID> {

    Optional<CallbackReceiptEntity> findByAttemptIdAndCallbackKey(UUID attemptId, UUID callbackKey);
}
