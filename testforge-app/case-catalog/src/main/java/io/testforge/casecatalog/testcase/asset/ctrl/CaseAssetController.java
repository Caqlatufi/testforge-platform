package io.testforge.casecatalog.testcase.asset.ctrl;

import io.testforge.casecatalog.testcase.asset.model.CaseAssetView;
import io.testforge.casecatalog.testcase.asset.service.CaseAssetContent;
import io.testforge.casecatalog.testcase.asset.service.CaseAssetService;
import io.testforge.casecatalog.ctrl.ApiResponse;
import io.testforge.casecatalog.testcase.service.TestCaseValidationException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class CaseAssetController {
    private final CaseAssetService service;

    public CaseAssetController(CaseAssetService service) {
        this.service = service;
    }

    @PostMapping(path = "/projects/{projectId}/assets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<CaseAssetView> upload(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file
    ) {
        try {
            return ApiResponse.success(service.store(
                    projectId, file.getOriginalFilename(), file.getContentType(), file.getBytes()
            ));
        } catch (IOException error) {
            throw new TestCaseValidationException("Asset 文件读取失败");
        }
    }

    @GetMapping("/projects/{projectId}/assets")
    public ApiResponse<List<CaseAssetView>> list(@PathVariable UUID projectId) {
        return ApiResponse.success(service.list(projectId));
    }

    @GetMapping("/assets/{assetId}")
    public ApiResponse<CaseAssetView> get(@PathVariable UUID assetId) {
        return ApiResponse.success(service.get(assetId));
    }

    @GetMapping("/assets/{assetId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID assetId) {
        CaseAssetContent content = service.content(assetId);
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(content.contentType()); }
        catch (IllegalArgumentException ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(content.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("X-TestForge-SHA256", content.sha256())
                .body(content.bytes());
    }
}
