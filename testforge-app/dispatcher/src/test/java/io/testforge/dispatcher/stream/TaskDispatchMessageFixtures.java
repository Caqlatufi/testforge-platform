package io.testforge.dispatcher.stream;

import io.testforge.dispatcher.port.outbound.RunnerType;
import io.testforge.dispatcher.port.outbound.TaskDispatchMessage;
import io.testforge.dispatcher.port.outbound.WorkerPlatform;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class TaskDispatchMessageFixtures {

    static final Instant PUBLISHED_AT = Instant.parse("2026-09-18T01:02:03Z");
    static final UUID MESSAGE_ID = UUID.fromString("40000000-0000-4000-8000-000000000001");

    private TaskDispatchMessageFixtures() {
    }

    static TaskDispatchMessage pytestMessage() {
        return message(RunnerType.PYTEST_HTTP, WorkerPlatform.LINUX, null);
    }

    static TaskDispatchMessage airtestMessage(WorkerPlatform platform) {
        return message(
                RunnerType.AIRTEST,
                platform,
                UUID.fromString("b0000000-0000-4000-8000-000000000001")
        );
    }

    private static TaskDispatchMessage message(
            RunnerType runner,
            WorkerPlatform platform,
            UUID deviceSlotId
    ) {
        return new TaskDispatchMessage(
                TaskDispatchMessage.SCHEMA_VERSION,
                MESSAGE_ID,
                PUBLISHED_AT,
                null,
                1,
                UUID.fromString("50000000-0000-4000-8000-000000000001"),
                UUID.fromString("30000000-0000-4000-8000-000000000001"),
                UUID.fromString("20000000-0000-4000-8000-000000000001"),
                UUID.fromString("60000000-0000-4000-8000-000000000001"),
                "1.0",
                new TaskDispatchMessage.Execution(
                        UUID.fromString("70000000-0000-4000-8000-000000000001"),
                        UUID.fromString("80000000-0000-4000-8000-000000000001"),
                        UUID.fromString("90000000-0000-4000-8000-000000000001"),
                        "ASSERTION",
                        "case:login-health",
                        runner,
                        platform,
                        List.of(runner == RunnerType.AIRTEST ? "WINDOWS_UI" : "HTTP"),
                        new TaskDispatchMessage.Script(
                                3,
                                "s3://testforge-scripts/login-health-v3.zip",
                                "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
                        ),
                        Map.of("expectedStatus", 200),
                        new TaskDispatchMessage.Environment(
                                UUID.fromString("a0000000-0000-4000-8000-000000000001"),
                                "http://127.0.0.1:8090",
                                Map.of("locale", "zh-CN"),
                                Map.of("apiToken", "secret/testforge/demo-token")
                        ),
                        30,
                        deviceSlotId
                ),
                new TaskDispatchMessage.Callbacks(
                        "http://testforge-app:8080/api/v1/attempts/20000000-0000-4000-8000-000000000001/start",
                        "http://testforge-app:8080/api/v1/attempts/20000000-0000-4000-8000-000000000001/heartbeat",
                        "http://testforge-app:8080/api/v1/attempts/20000000-0000-4000-8000-000000000001/callback",
                        "http://testforge-app:8080/api/v1/attempts/20000000-0000-4000-8000-000000000001/artifacts"
                ),
                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                null
        );
    }
}
