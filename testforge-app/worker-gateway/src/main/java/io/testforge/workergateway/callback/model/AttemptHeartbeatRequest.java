package io.testforge.workergateway.callback.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AttemptHeartbeatRequest(
        @NotBlank @Size(max = 128)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]*$") String workerId,
        @NotNull UUID leaseToken,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal progress,
        @NotNull Instant at
) {
}
