package io.testforge.casecatalog.testcase.entity;

import io.testforge.casecatalog.testcase.model.TestCaseKind;
import io.testforge.casecatalog.testcase.model.CaseScope;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Entity
@Table(
        name = "case_catalog_test_case",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_case_catalog_test_case_target_name",
                columnNames = {"target_id", "name"}
        )
)
public class TestCaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 16, columnDefinition = "varchar(16) default 'PROJECT'")
    private CaseScope scope = CaseScope.PROJECT;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private TestCaseKind kind;

    @Column(name = "parameter_schema_json", nullable = false, columnDefinition = "TEXT")
    private String parameterSchemaJson;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "execution_requirement_json", columnDefinition = "TEXT")
    private String executionRequirementJson;

    @Column(name = "script_version_id")
    private UUID scriptVersionId;

    @Column(name = "definition_yaml", columnDefinition = "TEXT")
    private String definitionYaml;

    @Column(name = "definition_digest", length = 71)
    private String definitionDigest;

    @Column(name = "script_asset_id")
    private UUID scriptAssetId;

    @Column(name = "script_entrypoint", length = 512)
    private String scriptEntrypoint;

    @Column(name = "script_checksum", length = 71)
    private String scriptChecksum;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "case_catalog_test_case_tag",
            joinColumns = @JoinColumn(name = "case_id")
    )
    @Column(name = "tag", nullable = false, length = 64)
    private Set<String> tags = new LinkedHashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestCaseEntity() {
    }

    public TestCaseEntity(
            UUID id,
            UUID projectId,
            UUID targetId,
            CaseScope scope,
            String name,
            TestCaseKind kind,
            String parameterSchemaJson,
            int timeoutSeconds,
            String executionRequirementJson,
            Set<String> tags,
            Instant createdAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.targetId = targetId;
        this.scope = scope == null ? CaseScope.PROJECT : scope;
        this.name = name;
        this.kind = kind;
        this.parameterSchemaJson = parameterSchemaJson;
        this.timeoutSeconds = timeoutSeconds;
        this.executionRequirementJson = executionRequirementJson;
        this.tags = new LinkedHashSet<>(tags);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void attachScriptVersion(UUID newScriptVersionId, Instant changedAt) {
        this.scriptVersionId = newScriptVersionId;
        this.updatedAt = changedAt;
    }

    public TestCaseEntity(UUID id, UUID projectId, UUID targetId, String name, TestCaseKind kind,
                          String parameterSchemaJson, int timeoutSeconds, Set<String> tags, Instant createdAt) {
        this(id, projectId, targetId, CaseScope.PROJECT, name, kind, parameterSchemaJson,
                timeoutSeconds, null, tags, createdAt);
    }

    public TestCaseEntity(UUID id, UUID projectId, UUID targetId, CaseScope scope, String name,
                          TestCaseKind kind, String parameterSchemaJson, int timeoutSeconds,
                          Set<String> tags, Instant createdAt) {
        this(id, projectId, targetId, scope, name, kind, parameterSchemaJson,
                timeoutSeconds, null, tags, createdAt);
    }

    public void update(
            String name,
            TestCaseKind kind,
            CaseScope scope,
            String parameterSchemaJson,
            int timeoutSeconds,
            String executionRequirementJson,
            Set<String> tags,
            Instant changedAt
    ) {
        this.name = name;
        this.kind = kind;
        this.scope = scope == null ? CaseScope.PROJECT : scope;
        this.parameterSchemaJson = parameterSchemaJson;
        this.timeoutSeconds = timeoutSeconds;
        this.executionRequirementJson = executionRequirementJson;
        this.tags = new LinkedHashSet<>(tags);
        this.updatedAt = changedAt;
    }

    public void applyDefinition(
            String definitionYaml,
            String definitionDigest,
            UUID scriptAssetId,
            String scriptEntrypoint,
            String scriptChecksum,
            String name,
            TestCaseKind kind,
            String parameterSchemaJson,
            int timeoutSeconds,
            String executionRequirementJson,
            Set<String> tags,
            Instant changedAt
    ) {
        this.definitionYaml = definitionYaml;
        this.definitionDigest = definitionDigest;
        this.scriptAssetId = scriptAssetId;
        this.scriptEntrypoint = scriptEntrypoint;
        this.scriptChecksum = scriptChecksum;
        this.scriptVersionId = null;
        update(name, kind, this.scope, parameterSchemaJson, timeoutSeconds, executionRequirementJson, tags, changedAt);
    }

    public void update(String name, TestCaseKind kind, CaseScope scope, String parameterSchemaJson,
                       int timeoutSeconds, Set<String> tags, Instant changedAt) {
        update(name, kind, scope, parameterSchemaJson, timeoutSeconds,
                this.executionRequirementJson, tags, changedAt);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTargetId() {
        return targetId;
    }

    public CaseScope getScope() { return scope == null ? CaseScope.PROJECT : scope; }

    public String getName() {
        return name;
    }

    public TestCaseKind getKind() {
        return kind;
    }

    public String getParameterSchemaJson() {
        return parameterSchemaJson;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public String getExecutionRequirementJson() {
        return executionRequirementJson;
    }

    public UUID getScriptVersionId() {
        return scriptVersionId;
    }

    public String getDefinitionYaml() { return definitionYaml; }
    public String getDefinitionDigest() { return definitionDigest; }
    public UUID getScriptAssetId() { return scriptAssetId; }
    public String getScriptEntrypoint() { return scriptEntrypoint; }
    public String getScriptChecksum() { return scriptChecksum; }
    public boolean hasDefinition() { return definitionYaml != null && !definitionYaml.isBlank(); }

    public Set<String> getTags() {
        // ElementCollection iteration order is database-dependent. Expose a
        // stable immutable order so API responses and repeatable tests do not
        // depend on Hibernate or JVM hash iteration details.
        return Collections.unmodifiableSet(new TreeSet<>(tags));
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
