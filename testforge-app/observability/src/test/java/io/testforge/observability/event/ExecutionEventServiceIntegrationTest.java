package io.testforge.observability.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.testforge.common.event.ExecutionEventCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = ExecutionEventServiceIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:execution_events;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop"
        }
)
class ExecutionEventServiceIntegrationTest {
    @Autowired ExecutionEventService service;

    @Test
    void appendsIdempotentlyAndReadsFromExclusiveCursor() {
        UUID runId = UUID.randomUUID();
        UUID firstKey = UUID.randomUUID();
        ExecutionEventCommand first = new ExecutionEventCommand(
                firstKey, runId, null, null, "RUN_CREATED",
                Instant.parse("2026-09-22T00:00:00Z"), Map.of("state", "QUEUED")
        );
        service.append(first);
        service.append(first);
        service.append(new ExecutionEventCommand(
                UUID.randomUUID(), runId, UUID.randomUUID(), null, "TASK_QUEUED",
                Instant.parse("2026-09-22T00:00:01Z"), Map.of("resourceMode", "PROCESS_POOL")
        ));

        var all = service.history(runId, 0, 100);
        assertThat(all).hasSize(2);
        assertThat(service.history(runId, all.getFirst().id(), 100))
                .extracting(event -> event.type())
                .containsExactly("TASK_QUEUED");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = ExecutionEventEntity.class)
    @EnableJpaRepositories(basePackageClasses = ExecutionEventRepository.class)
    static class TestApplication {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean ExecutionEventService executionEventService(
                ExecutionEventRepository repository, ObjectMapper objectMapper
        ) { return new ExecutionEventService(repository, objectMapper); }
    }
}
