package io.testforge.casecatalog.suite.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "case_catalog_test_suite",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_case_catalog_suite_target_name",
                columnNames = {"project_id", "target_id", "name"}
        )
)
public class TestSuiteEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "target_id", nullable = false, updatable = false)
    private UUID targetId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "case_catalog_test_suite_member",
            joinColumns = @JoinColumn(name = "suite_id")
    )
    @Column(name = "case_id", nullable = false)
    private Set<UUID> caseIds = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "case_catalog_test_suite_tag",
            joinColumns = @JoinColumn(name = "suite_id")
    )
    @Column(name = "tag", nullable = false, length = 64)
    private Set<String> tags = new LinkedHashSet<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "case_catalog_test_suite_parameter",
            joinColumns = @JoinColumn(name = "suite_id")
    )
    @MapKeyColumn(name = "binding_key", length = 100)
    @Column(name = "value_json", nullable = false, columnDefinition = "TEXT")
    private Map<String, String> parameterBindingsJson = new LinkedHashMap<>();

    @Version
    @Column(name = "version", nullable = false)
    private Long persistenceVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TestSuiteEntity() {
    }

    public TestSuiteEntity(
            UUID id,
            UUID projectId,
            UUID targetId,
            String name,
            Set<UUID> caseIds,
            Set<String> tags,
            Map<String, String> parameterBindingsJson,
            Instant createdAt
    ) {
        this.id = id;
        this.projectId = projectId;
        this.targetId = targetId;
        this.name = name;
        this.caseIds.addAll(caseIds);
        this.tags.addAll(tags);
        this.parameterBindingsJson.putAll(parameterBindingsJson);
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public void update(
            String name,
            Set<UUID> caseIds,
            Set<String> tags,
            Map<String, String> parameterBindingsJson,
            Instant updatedAt
    ) {
        this.name = name;
        this.caseIds.clear();
        this.caseIds.addAll(caseIds);
        this.tags.clear();
        this.tags.addAll(tags);
        this.parameterBindingsJson.clear();
        this.parameterBindingsJson.putAll(parameterBindingsJson);
        this.updatedAt = updatedAt;
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

    public String getName() {
        return name;
    }

    public Set<UUID> getCaseIds() {
        return caseIds;
    }

    public Set<String> getTags() {
        return tags;
    }

    public Map<String, String> getParameterBindingsJson() {
        return parameterBindingsJson;
    }

    public long getVersion() {
        return persistenceVersion == null ? 1L : persistenceVersion + 1L;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
