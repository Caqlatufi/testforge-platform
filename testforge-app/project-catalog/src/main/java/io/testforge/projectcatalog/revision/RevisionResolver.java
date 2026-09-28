package io.testforge.projectcatalog.revision;

import java.util.List;

public interface RevisionResolver {

    ResolvedRevision resolve(String repositoryUrl, String defaultBranch, RevisionSelector selector);

    List<RevisionOption> list(String repositoryUrl, String defaultBranch, RevisionType type);
}
