package io.testforge.casecatalog.testcase.entity;

import io.testforge.casecatalog.testcase.model.ScriptRunner;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(
        name = "case_catalog_script_version",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_case_catalog_script_version_case_version",
                columnNames = {"case_id", "version_no"}
        )
)
public class TestScriptVersionEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "runner", nullable = false, updatable = false, length = 32)
    private ScriptRunner runner;

    @Column(name = "source_ref", nullable = false, updatable = false, length = 1024)
    private String sourceRef;

    @Column(name = "checksum", nullable = false, updatable = false, length = 71)
    private String checksum;

    @Column(name = "version_no", nullable = false, updatable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected TestScriptVersionEntity() {
    }

    public TestScriptVersionEntity(
            UUID id,
            UUID caseId,
            ScriptRunner runner,
            String sourceRef,
            String checksum,
            int version,
            Instant createdAt
    ) {
        this.id = id;
        this.caseId = caseId;
        this.runner = runner;
        this.sourceRef = sourceRef;
        this.checksum = checksum;
        this.version = version;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public ScriptRunner getRunner() {
        return runner;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public String getChecksum() {
        return checksum;
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
