package io.testforge.casecatalog.testcase.asset.entity;

import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Entity
@Table(
        name = "case_catalog_asset",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_case_catalog_asset_project_sha256",
                columnNames = {"project_id", "sha256"}
        )
)
public class CaseAssetEntity {
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "file_name", nullable = false, updatable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, updatable = false, length = 128)
    private String contentType;

    @Column(name = "size_bytes", nullable = false, updatable = false)
    private long sizeBytes;

    @Column(name = "sha256", nullable = false, updatable = false, length = 71)
    private String sha256;

    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", nullable = false, updatable = false, columnDefinition = "LONGBLOB")
    private byte[] content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CaseAssetEntity() {
    }

    public CaseAssetEntity(UUID id, UUID projectId, String fileName, String contentType,
                           long sizeBytes, String sha256, byte[] content, Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.content = Arrays.copyOf(content, content.length);
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public byte[] getContent() { return Arrays.copyOf(content, content.length); }
    public Instant getCreatedAt() { return createdAt; }
}
