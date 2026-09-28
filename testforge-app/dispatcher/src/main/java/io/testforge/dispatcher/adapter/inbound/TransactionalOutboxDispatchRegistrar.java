package io.testforge.dispatcher.adapter.inbound;

import io.testforge.dispatcher.outbox.service.OutboxRegistrationService;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistration;
import io.testforge.runorchestrator.port.dispatch.TaskDispatchRegistrationPort;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 将任务进入 QUEUED 与 Outbox 登记绑定在同一个 Spring 事务中。
 */
@Component
public class TransactionalOutboxDispatchRegistrar implements TaskDispatchRegistrationPort {

    static final String AGGREGATE_TYPE = "TASK";
    static final String EVENT_TYPE = "TASK_READY";

    private final OutboxRegistrationService registrationService;

    public TransactionalOutboxDispatchRegistrar(OutboxRegistrationService registrationService) {
        this.registrationService = Objects.requireNonNull(registrationService,
                "registrationService must not be null");
    }

    @Override
    public void register(TaskDispatchRegistration registration) {
        Objects.requireNonNull(registration, "registration must not be null");
        registrationService.registerAvailableAt(
                eventKey(registration),
                registration.taskId(),
                AGGREGATE_TYPE,
                EVENT_TYPE,
                payload(registration),
                registration.queuedAt()
        );
    }

    private String eventKey(TaskDispatchRegistration registration) {
        return "task-ready:" + registration.taskId() + ":attempt:" + registration.attemptNo();
    }

    private Map<String, Object> payload(TaskDispatchRegistration registration) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("taskId", registration.taskId());
        payload.put("runId", registration.runId());
        payload.put("runner", registration.runner());
        payload.put("resourceMode", registration.resourceMode());
        payload.put("platform", registration.platform());
        payload.put("sourceRef", registration.sourceRef());
        payload.put("scriptChecksum", registration.scriptChecksum());
        payload.put("timeoutSeconds", registration.timeoutSeconds());
        payload.put("requiredFeatures", registration.requiredFeatures());
        payload.put("parameters", registration.parameters());
        payload.put("priority", registration.priority());
        payload.put("attemptNo", registration.attemptNo());
        payload.put("queuedAt", registration.queuedAt());
        return payload;
    }
}
