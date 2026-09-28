package io.testforge.app.monitoring;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
public class PlatformMonitoringController {
    private final PlatformMonitoringService service;

    public PlatformMonitoringController(PlatformMonitoringService service) {
        this.service = service;
    }

    @GetMapping("/resources")
    public Map<String, PlatformResourceSnapshot> resources() {
        return Map.of("data", service.snapshot());
    }
}
