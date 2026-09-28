package io.testforge.cicdgateway.catalog.ctrl;

import io.testforge.cicdgateway.catalog.model.PipelineRef;
import io.testforge.cicdgateway.catalog.service.PipelineCatalogService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/pipelines")
public class PipelineCatalogController {
    private final PipelineCatalogService service;

    public PipelineCatalogController(PipelineCatalogService service) { this.service = service; }

    @GetMapping
    public Map<String, List<PipelineRef>> list(@PathVariable UUID projectId) {
        return Map.of("data", service.list(projectId));
    }

}
