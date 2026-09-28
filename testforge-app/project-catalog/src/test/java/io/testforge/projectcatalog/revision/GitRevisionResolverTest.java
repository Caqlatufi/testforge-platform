package io.testforge.projectcatalog.revision;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitRevisionResolverTest {

    @TempDir
    Path tempDirectory;

    private final GitRevisionResolver resolver = new GitRevisionResolver();

    @Test
    void shouldResolveDefaultBranchBranchTagAndCommitToFullSha() throws IOException, InterruptedException {
        Path repository = initializeRepository();
        String firstCommit = git(repository, "rev-parse", "HEAD").trim();
        git(repository, "tag", "v1.0.0");

        assertThat(resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.DEFAULT_BRANCH, null)
        ).commitSha()).isEqualTo(firstCommit);
        assertThat(resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.BRANCH, "main")
        ).commitSha()).isEqualTo(firstCommit);
        assertThat(resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.TAG, "v1.0.0")
        ).commitSha()).isEqualTo(firstCommit);
        assertThat(resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.COMMIT, firstCommit.toUpperCase())
        ).commitSha()).isEqualTo(firstCommit);
    }

    @Test
    void shouldResolveBranchAtCallTimeWithoutMutatingPreviousResult() throws IOException, InterruptedException {
        Path repository = initializeRepository();
        ResolvedRevision first = resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.DEFAULT_BRANCH, null)
        );

        Files.writeString(repository.resolve("sample.txt"), "second", StandardCharsets.UTF_8);
        git(repository, "add", "sample.txt");
        git(repository, "commit", "-m", "second");

        ResolvedRevision second = resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.DEFAULT_BRANCH, null)
        );

        assertThat(second.commitSha()).isNotEqualTo(first.commitSha());
        assertThat(first.commitSha()).hasSize(40);
    }

    @Test
    void shouldRejectMissingReferenceAndShortCommit() throws IOException, InterruptedException {
        Path repository = initializeRepository();

        assertThatThrownBy(() -> resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.BRANCH, "missing")
        )).isInstanceOf(RevisionResolutionException.class)
                .hasMessageContaining("不存在");
        assertThatThrownBy(() -> resolver.resolve(
                repository.toString(), "main", new RevisionSelector(RevisionType.COMMIT, "abc1234")
        )).isInstanceOf(RevisionResolutionException.class)
                .hasMessageContaining("40 位");
    }

    @Test
    void shouldListBranchesAndTagsWithCommitMetadata() throws IOException, InterruptedException {
        Path repository = initializeRepository();
        git(repository, "tag", "v1.0.0");
        git(repository, "branch", "feature/catalog");

        List<RevisionOption> branches = resolver.list(repository.toString(), "main", RevisionType.BRANCH);
        List<RevisionOption> tags = resolver.list(repository.toString(), "main", RevisionType.TAG);

        assertThat(branches).extracting(RevisionOption::name).containsExactly("main", "feature/catalog");
        assertThat(branches.getFirst().defaultBranch()).isTrue();
        assertThat(branches.getFirst().commitMessage()).isEqualTo("first");
        assertThat(branches.getFirst().commitSha()).hasSize(40);
        assertThat(tags).singleElement().satisfies(tag -> {
            assertThat(tag.name()).isEqualTo("v1.0.0");
            assertThat(tag.commitMessage()).isEqualTo("first");
        });
    }

    @Test
    void shouldTreatProjectSubdirectoryAsPartOfItsContainingLocalRepository() throws IOException, InterruptedException {
        Path repository = initializeRepository();
        Path projectDirectory = Files.createDirectories(repository.resolve("testforge-platform"));
        String commit = git(repository, "rev-parse", "HEAD").trim();

        assertThat(resolver.list(projectDirectory.toString(), "main", RevisionType.BRANCH))
                .extracting(RevisionOption::name).contains("main");
        assertThat(resolver.resolve(projectDirectory.toString(), "main",
                new RevisionSelector(RevisionType.DEFAULT_BRANCH, null)).commitSha()).isEqualTo(commit);
    }

    private Path initializeRepository() throws IOException, InterruptedException {
        Path repository = tempDirectory.resolve("repository");
        Files.createDirectories(repository);
        git(repository, "init");
        git(repository, "config", "user.email", "testforge@example.test");
        git(repository, "config", "user.name", "TestForge");
        git(repository, "branch", "-M", "main");
        Files.writeString(repository.resolve("sample.txt"), "first", StandardCharsets.UTF_8);
        git(repository, "add", "sample.txt");
        git(repository, "commit", "-m", "first");
        return repository;
    }

    private String git(Path repository, String... arguments) throws IOException, InterruptedException {
        String[] command = new String[arguments.length + 3];
        command[0] = "git";
        command[1] = "-C";
        command[2] = repository.toString();
        System.arraycopy(arguments, 0, command, 3, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }
}
