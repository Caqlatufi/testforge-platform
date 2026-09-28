package io.testforge.workergateway.callback.repo;

import io.testforge.workergateway.callback.entity.CallbackReceiptEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
        classes = CallbackReceiptRepositoryIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:callback_receipt;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=BINARY",
        }
)
class CallbackReceiptRepositoryIntegrationTest {

    @Autowired
    private CallbackReceiptRepository repository;

    @Test
    void attemptAndCallbackKeyFormTheIdempotencyBoundary() {
        UUID attemptId = UUID.randomUUID();
        UUID callbackKey = UUID.randomUUID();
        repository.saveAndFlush(receipt(attemptId, callbackKey, "a".repeat(64)));

        assertThatThrownBy(() -> repository.saveAndFlush(
                receipt(attemptId, callbackKey, "b".repeat(64))
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void sameCallbackKeyMayBeUsedByDifferentAttempts() {
        UUID callbackKey = UUID.randomUUID();
        repository.saveAndFlush(receipt(UUID.randomUUID(), callbackKey, "a".repeat(64)));
        repository.saveAndFlush(receipt(UUID.randomUUID(), callbackKey, "a".repeat(64)));

        assertThat(repository.count()).isEqualTo(2);
    }

    private CallbackReceiptEntity receipt(UUID attemptId, UUID callbackKey, String payloadHash) {
        return new CallbackReceiptEntity(
                UUID.randomUUID(), attemptId, callbackKey, payloadHash,
                Instant.parse("2026-09-18T06:00:00Z")
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = CallbackReceiptEntity.class)
    @EnableJpaRepositories(basePackageClasses = CallbackReceiptRepository.class)
    static class TestApplication {
    }
}
