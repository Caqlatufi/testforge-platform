package io.testforge.cicdgateway.deployment.entity;

import io.testforge.cicdgateway.deployment.model.DeploymentCallbackCommand;
import io.testforge.cicdgateway.deployment.model.DeploymentState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TargetDeploymentEntityTest {
    @Test
    void acceptsSameCallbackExactlyOnceAndRejectsChangedPayload() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        var deployment = deployment(now);
        UUID callbackKey = UUID.randomUUID();
        var ready = new DeploymentCallbackCommand(callbackKey, "job#1", DeploymentState.READY,
                "http://target", "jenkins:job#1", "sha256:artifact", "ready");

        assertThat(deployment.callback(ready, "sha256:payload", now.plusSeconds(2), 600)).isTrue();
        assertThat(deployment.callback(ready, "sha256:payload", now.plusSeconds(3), 600)).isFalse();
        assertThat(deployment.getState()).isEqualTo(DeploymentState.READY);
        assertThatThrownBy(() -> deployment.callback(ready, "sha256:changed", now.plusSeconds(4), 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("幂等键");
    }

    @Test
    void expiresAndCanRestartTheSameImmutableDeploymentKey() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        var deployment = deployment(now);
        deployment.callback(new DeploymentCallbackCommand(UUID.randomUUID(), "job#1", DeploymentState.READY,
                "http://target", null, null, "ready"), "sha256:ready", now, 60);

        assertThat(deployment.expire(now.plusSeconds(59))).isFalse();
        assertThat(deployment.expire(now.plusSeconds(60))).isTrue();
        deployment.restart(now.plusSeconds(61));
        assertThat(deployment.getState()).isEqualTo(DeploymentState.PENDING);
        assertThat(deployment.getEndpoint()).isNull();
    }

    @Test
    void failedDeploymentCanRetryTheSameImmutableDeploymentKey() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        var deployment = deployment(now);
        deployment.callback(new DeploymentCallbackCommand(UUID.randomUUID(), "job#2", DeploymentState.FAILED,
                null, null, null, "checkout failed"), "sha256:failed", now.plusSeconds(10), 60);

        deployment.restart(now.plusSeconds(11));

        assertThat(deployment.getState()).isEqualTo(DeploymentState.PENDING);
        assertThat(deployment.getProviderRunId()).isNull();
        assertThat(deployment.getSummary()).isNull();
    }

    @Test
    void staleActiveDeploymentFailsButTerminalDeploymentIsNeverOverwritten() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        var stale = deployment(now);
        stale.triggered("queue/64", now.plusSeconds(1));

        assertThat(stale.failIfActiveBefore(now.plusSeconds(10), now.plusSeconds(20), "callback timeout")).isTrue();
        assertThat(stale.getState()).isEqualTo(DeploymentState.FAILED);
        assertThat(stale.getSummary()).isEqualTo("callback timeout");
        assertThat(stale.failIfActiveBefore(now.plusSeconds(30), now.plusSeconds(40), "late timeout")).isFalse();
        assertThat(stale.getSummary()).isEqualTo("callback timeout");
    }

    @Test
    void activeDeploymentCanBeFailedImmediatelyWhenOwningRunIsCancelled() {
        Instant now = Instant.parse("2026-09-22T00:00:00Z");
        var deployment = deployment(now);
        deployment.triggered("queue/65", now.plusSeconds(1));

        assertThat(deployment.failIfActive(now.plusSeconds(2), "owning run cancelled")).isTrue();
        assertThat(deployment.getState()).isEqualTo(DeploymentState.FAILED);
        assertThat(deployment.getSummary()).isEqualTo("owning run cancelled");
        assertThat(deployment.failIfActive(now.plusSeconds(3), "late cancel")).isFalse();
        assertThat(deployment.getSummary()).isEqualTo("owning run cancelled");
    }

    private TargetDeploymentEntity deployment(Instant now) {
        return new TargetDeploymentEntity(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "a".repeat(40), "sha256:" + "b".repeat(64), now);
    }
}
