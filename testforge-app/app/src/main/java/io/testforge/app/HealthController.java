package io.testforge.app;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final HealthEndpoint healthEndpoint;

    public HealthController(HealthEndpoint healthEndpoint) {
        this.healthEndpoint = healthEndpoint;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean isUp = Status.UP.equals(healthEndpoint.health().getStatus());
        HttpStatus responseStatus = isUp
                ? HttpStatus.OK
                : HttpStatus.SERVICE_UNAVAILABLE;
        HealthResponse response = new HealthResponse(isUp ? "UP" : "DEGRADED");
        return ResponseEntity.status(responseStatus).body(response);
    }

    public record HealthResponse(String status) {
    }
}
