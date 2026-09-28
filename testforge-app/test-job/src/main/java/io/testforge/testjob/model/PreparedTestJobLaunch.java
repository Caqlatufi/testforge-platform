package io.testforge.testjob.model;

import io.testforge.casecatalog.workflow.compile.model.PublishedWorkflowVersionView;
import io.testforge.projectcatalog.revision.ResolvedRevision;

import java.util.UUID;

public record PreparedTestJobLaunch(
        TestJobView job,
        TestJobSnapshot snapshot,
        PublishedWorkflowVersionView workflow,
        ResolvedRevision revision,
        UUID deploymentProfileId,
        String providerEnvironmentKey
) { }
