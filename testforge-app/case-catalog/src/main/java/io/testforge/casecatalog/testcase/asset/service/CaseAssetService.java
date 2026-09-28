package io.testforge.casecatalog.testcase.asset.service;

import io.testforge.casecatalog.testcase.asset.entity.CaseAssetEntity;
import io.testforge.casecatalog.testcase.asset.model.CaseAssetView;
import io.testforge.casecatalog.testcase.asset.repo.CaseAssetRepository;
import io.testforge.casecatalog.testcase.service.TestCaseConflictException;
import io.testforge.casecatalog.testcase.service.TestCaseNotFoundException;
import io.testforge.casecatalog.testcase.service.TestCaseValidationException;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CaseAssetService {
    public static final long MAX_BYTES = 20L * 1024 * 1024;
    public static final long MAX_EXPANDED_BYTES = 200L * 1024 * 1024;

    private final CaseAssetRepository repository;
    private final ProjectCatalogService projectCatalogService;

    public CaseAssetService(CaseAssetRepository repository, ProjectCatalogService projectCatalogService) {
        this.repository = repository;
        this.projectCatalogService = projectCatalogService;
    }

    @Transactional
    public CaseAssetView store(UUID projectId, String fileName, String contentType, byte[] content) {
        projectCatalogService.getProject(projectId);
        byte[] bytes = validateContent(content);
        String safeName = safeFileName(fileName);
        if (looksLikeZip(safeName, contentType, bytes)) validateZip(bytes);
        List<String> entrypoints = inferEntrypoints(safeName, contentType, bytes);
        if (entrypoints.isEmpty()) {
            throw new TestCaseValidationException("Case 脚本必须包含一个可执行的 .air 或 .py 主入口");
        }
        if (entrypoints.size() > 1) {
            throw new TestCaseValidationException(
                    "Case 脚本只允许一个主入口，检测到: " + String.join(", ", entrypoints)
            );
        }
        String sha256 = digest(bytes);
        var existing = repository.findByProjectIdAndSha256(projectId, sha256);
        if (existing.isPresent()) return toView(existing.get());
        var entity = new CaseAssetEntity(
                UUID.randomUUID(), projectId, safeName, normalizeContentType(contentType),
                bytes.length, sha256, bytes, Instant.now()
        );
        try {
            return toView(repository.saveAndFlush(entity));
        } catch (DataIntegrityViolationException conflict) {
            return repository.findByProjectIdAndSha256(projectId, sha256)
                    .map(this::toView)
                    .orElseThrow(() -> new TestCaseConflictException("Asset 并发上传冲突，请重试"));
        }
    }

    public CaseAssetView storeInline(UUID projectId, String fileName, String content) {
        if (content == null || content.isBlank()) {
            throw new TestCaseValidationException("inline script content 不能为空");
        }
        return store(projectId, fileName, "text/x-python", content.getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    public List<CaseAssetView> list(UUID projectId) {
        projectCatalogService.getProject(projectId);
        return repository.findAllByProjectIdOrderByCreatedAtDesc(projectId).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public CaseAssetView get(UUID assetId) {
        return toView(require(assetId));
    }

    @Transactional(readOnly = true)
    public CaseAssetView requireOwned(UUID projectId, UUID assetId) {
        CaseAssetEntity entity = require(assetId);
        if (!entity.getProjectId().equals(projectId)) {
            throw new TestCaseValidationException("Asset 不属于当前项目: " + assetId);
        }
        return toView(entity);
    }

    @Transactional(readOnly = true)
    public CaseAssetContent content(UUID assetId) {
        CaseAssetEntity entity = require(assetId);
        return new CaseAssetContent(
                entity.getFileName(), entity.getContentType(), entity.getSha256(), entity.getContent()
        );
    }

    private CaseAssetEntity require(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new TestCaseNotFoundException("Asset 不存在: " + id));
    }

    private CaseAssetView toView(CaseAssetEntity entity) {
        String uri = "testforge://assets/" + entity.getId();
        List<String> entrypoints = inferEntrypoints(
                entity.getFileName(), entity.getContentType(), entity.getContent()
        );
        String yamlSnippet = "script:\n  type: asset\n  asset: " + uri;
        if (entrypoints.size() == 1) {
            yamlSnippet += "\n  entrypoint: " + yamlQuote(entrypoints.getFirst());
        }
        return new CaseAssetView(
                entity.getId(), entity.getProjectId(), entity.getFileName(), entity.getContentType(),
                entity.getSizeBytes(), entity.getSha256(), uri, entrypoints, yamlSnippet,
                entity.getCreatedAt()
        );
    }

    private static List<String> inferEntrypoints(String fileName, String contentType, byte[] bytes) {
        if (!looksLikeZip(fileName, contentType, bytes)) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            return lower.endsWith(".py") || lower.endsWith(".air") ? List.of(fileName) : List.of();
        }
        Set<String> airtestRoots = new LinkedHashSet<>();
        Set<String> pythonFiles = new LinkedHashSet<>();
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().replace('\\', '/');
                String[] parts = name.split("/");
                StringBuilder prefix = new StringBuilder();
                for (String part : parts) {
                    if (!prefix.isEmpty()) prefix.append('/');
                    prefix.append(part);
                    if (part.toLowerCase(Locale.ROOT).endsWith(".air")) {
                        airtestRoots.add(prefix.toString());
                        break;
                    }
                }
                if (!name.contains("/") && name.toLowerCase(Locale.ROOT).endsWith(".py")) {
                    pythonFiles.add(name);
                }
            }
        } catch (IOException error) {
            throw new TestCaseValidationException("ZIP 文件无法读取");
        }
        List<String> candidates = airtestRoots.isEmpty() ? List.copyOf(pythonFiles) : List.copyOf(airtestRoots);
        return candidates.stream().sorted().toList();
    }

    private static String yamlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static byte[] validateContent(byte[] content) {
        if (content == null || content.length == 0) throw new TestCaseValidationException("Asset 文件不能为空");
        if (content.length > MAX_BYTES) throw new TestCaseValidationException("Asset 文件不能超过 20 MiB");
        return content.clone();
    }

    private static String safeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) throw new TestCaseValidationException("Asset 文件名不能为空");
        String normalized = fileName.replace('\\', '/');
        String leaf = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (leaf.isBlank() || leaf.equals(".") || leaf.equals("..") || leaf.length() > 255) {
            throw new TestCaseValidationException("Asset 文件名不合法");
        }
        return leaf;
    }

    private static String normalizeContentType(String value) {
        return value == null || value.isBlank() ? "application/octet-stream" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeZip(String fileName, String contentType, byte[] bytes) {
        return fileName.toLowerCase(Locale.ROOT).endsWith(".zip")
                || (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("zip"))
                || (bytes.length >= 4 && bytes[0] == 'P' && bytes[1] == 'K');
    }

    private static void validateZip(byte[] bytes) {
        int entries = 0;
        long expandedBytes = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = input.getNextEntry()) != null; ) {
                entries++;
                if (entries > 2000) throw new TestCaseValidationException("ZIP 文件条目超过 2000 个");
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.matches("^[A-Za-z]:.*")
                        || List.of(name.split("/")).contains("..")) {
                    throw new TestCaseValidationException("ZIP 包含不安全路径: " + entry.getName());
                }
                for (int read; (read = input.read(buffer)) >= 0; ) {
                    expandedBytes += read;
                    if (expandedBytes > MAX_EXPANDED_BYTES) {
                        throw new TestCaseValidationException("ZIP 解压后内容不能超过 200 MiB");
                    }
                }
            }
        } catch (IOException error) {
            throw new TestCaseValidationException("ZIP 文件无法读取");
        }
        if (entries == 0) throw new TestCaseValidationException("ZIP 文件没有内容");
    }

    private static String digest(byte[] content) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
