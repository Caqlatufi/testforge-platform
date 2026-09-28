package io.testforge.projectcatalog.revision;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Comparator;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class GitRevisionResolver implements RevisionResolver {

    private static final Pattern FULL_COMMIT = Pattern.compile("^[0-9a-fA-F]{40}$");
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private final Clock clock;
    private final Duration timeout;

    public GitRevisionResolver() {
        this(Clock.systemUTC(), DEFAULT_TIMEOUT);
    }

    GitRevisionResolver(Clock clock, Duration timeout) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.timeout = Objects.requireNonNull(timeout, "timeout must not be null");
    }

    @Override
    public ResolvedRevision resolve(
            String repositoryUrl,
            String defaultBranch,
            RevisionSelector selector
    ) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new RevisionResolutionException(
                    "TARGET_SOURCE_NOT_CONFIGURED",
                    "Target 未配置 repositoryUrl"
            );
        }
        if (selector == null || selector.type() == null) {
            throw new RevisionResolutionException("VALIDATION_ERROR", "版本选择类型不能为空");
        }

        return switch (selector.type()) {
            case DEFAULT_BRANCH -> resolveBranch(
                    repositoryUrl,
                    requireRef(defaultBranch, "Target 未配置 defaultBranch"),
                    RevisionType.DEFAULT_BRANCH
            );
            case BRANCH -> resolveBranch(
                    repositoryUrl,
                    requireRef(selector.value(), "BRANCH value 不能为空"),
                    RevisionType.BRANCH
            );
            case TAG -> resolveTag(repositoryUrl, requireRef(selector.value(), "TAG value 不能为空"));
            case COMMIT -> resolveCommit(selector.value());
        };
    }

    @Override
    public List<RevisionOption> list(String repositoryUrl, String defaultBranch, RevisionType type) {
        if (repositoryUrl == null || repositoryUrl.isBlank()) {
            throw new RevisionResolutionException("TARGET_SOURCE_NOT_CONFIGURED", "Target 未配置 repositoryUrl");
        }
        if (type != RevisionType.BRANCH && type != RevisionType.TAG) {
            throw new RevisionResolutionException("VALIDATION_ERROR", "版本目录只支持 BRANCH 或 TAG");
        }
        Path local = localRepository(repositoryUrl);
        if (local != null) return listLocal(local, defaultBranch, type);

        Path mirror = null;
        try {
            mirror = Files.createTempDirectory("testforge-revision-catalog-");
            runGit("clone", "--bare", "--filter=blob:none", "--depth=1", "--no-single-branch",
                    "--tags", repositoryUrl, mirror.toString());
            return listLocal(mirror, defaultBranch, type);
        } catch (IOException exception) {
            throw new RevisionResolutionException("REVISION_RESOLUTION_FAILED", "无法创建 Git 版本目录缓存");
        } finally {
            deleteRecursively(mirror);
        }
    }

    private List<RevisionOption> listLocal(Path repository, String defaultBranch, RevisionType type) {
        String namespace = type == RevisionType.BRANCH ? "refs/heads" : "refs/tags";
        return runGit("-C", repository.toString(), "for-each-ref", "--format=%(refname:short)", namespace)
                .stream()
                .limit(200)
                .map(name -> option(repository, defaultBranch, type, name.strip()))
                .sorted(Comparator.comparing(RevisionOption::defaultBranch).reversed()
                        .thenComparing(RevisionOption::committedAt, Comparator.reverseOrder())
                        .thenComparing(RevisionOption::name))
                .toList();
    }

    private RevisionOption option(Path repository, String defaultBranch, RevisionType type, String name) {
        String reference = (type == RevisionType.BRANCH ? "refs/heads/" : "refs/tags/") + name;
        String commit = runGit("-C", repository.toString(), "rev-parse", reference + "^{commit}").getFirst().strip();
        List<String> metadata = runGit("-C", repository.toString(), "show", "-s", "--format=%s%x00%cI", commit);
        String[] values = metadata.getFirst().split("\u0000", 2);
        String message = values[0].isBlank() ? "（无提交描述）" : values[0].strip();
        Instant committedAt = values.length > 1 ? Instant.parse(values[1].strip()) : Instant.EPOCH;
        return new RevisionOption(type, name, commit.toLowerCase(Locale.ROOT), message, committedAt,
                type == RevisionType.BRANCH && name.equals(defaultBranch));
    }

    private Path localRepository(String repositoryUrl) {
        try {
            Path candidate = Path.of(repositoryUrl).toAbsolutePath().normalize();
            if (!Files.isDirectory(candidate)) return null;
            Path current = candidate;
            while (current != null) {
                if (Files.isDirectory(current.resolve(".git"))
                        || Files.isRegularFile(current.resolve(".git"))
                        || Files.isRegularFile(current.resolve("HEAD"))) return current;
                current = current.getParent();
            }
        } catch (RuntimeException ignored) {
            // URL 或非本机路径由临时 bare clone 处理。
        }
        return null;
    }

    private void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private ResolvedRevision resolveBranch(String repositoryUrl, String branch, RevisionType requestedType) {
        String reference = "refs/heads/" + branch;
        Path local = localRepository(repositoryUrl);
        if (local != null) {
            List<String> matches = runGit("-C", local.toString(), "for-each-ref", "--format=%(objectname)", reference);
            if (matches.isEmpty()) {
                throw new RevisionResolutionException("REVISION_NOT_FOUND", "Git 引用不存在: " + reference);
            }
            String commit = runGit("-C", local.toString(), "rev-parse", reference + "^{commit}").getFirst().strip();
            return resolved(requestedType, branch, commit);
        }
        String commit = requireRemoteCommit(
                runGit("ls-remote", "--heads", repositoryUrl, reference),
                reference
        );
        return resolved(requestedType, branch, commit);
    }

    private ResolvedRevision resolveTag(String repositoryUrl, String tag) {
        String reference = "refs/tags/" + tag;
        Path local = localRepository(repositoryUrl);
        if (local != null) {
            List<String> matches = runGit("-C", local.toString(), "for-each-ref", "--format=%(objectname)", reference);
            if (matches.isEmpty()) {
                throw new RevisionResolutionException("REVISION_NOT_FOUND", "Git Tag 不存在: " + tag);
            }
            String commit = runGit("-C", local.toString(), "rev-parse", reference + "^{commit}").getFirst().strip();
            return resolved(RevisionType.TAG, tag, commit);
        }
        List<String> output = runGit(
                "ls-remote",
                "--tags",
                repositoryUrl,
                reference,
                reference + "^{}"
        );
        String peeled = findCommit(output, reference + "^{}");
        String commit = peeled == null ? findCommit(output, reference) : peeled;
        if (commit == null) {
            throw new RevisionResolutionException("REVISION_NOT_FOUND", "Git Tag 不存在: " + tag);
        }
        return resolved(RevisionType.TAG, tag, commit);
    }

    private ResolvedRevision resolveCommit(String value) {
        if (value == null || !FULL_COMMIT.matcher(value.trim()).matches()) {
            throw new RevisionResolutionException("VALIDATION_ERROR", "COMMIT 必须是完整的 40 位 SHA");
        }
        return resolved(RevisionType.COMMIT, value.trim(), value.trim());
    }

    private ResolvedRevision resolved(RevisionType type, String requestedValue, String commit) {
        return new ResolvedRevision(
                type,
                requestedValue,
                commit.toLowerCase(Locale.ROOT),
                Instant.now(clock)
        );
    }

    private String requireRemoteCommit(List<String> output, String reference) {
        String commit = findCommit(output, reference);
        if (commit == null) {
            throw new RevisionResolutionException("REVISION_NOT_FOUND", "Git 引用不存在: " + reference);
        }
        return commit;
    }

    private String findCommit(List<String> output, String reference) {
        for (String line : output) {
            String[] parts = line.strip().split("\\s+", 2);
            if (parts.length == 2 && parts[1].equals(reference) && FULL_COMMIT.matcher(parts[0]).matches()) {
                return parts[0];
            }
        }
        return null;
    }

    private String requireRef(String value, String message) {
        if (value == null || value.isBlank() || value.length() > 255) {
            throw new RevisionResolutionException("VALIDATION_ERROR", message);
        }
        String ref = value.trim();
        if (ref.startsWith("-") || ref.contains("..") || ref.contains("@{")
                || ref.chars().anyMatch(Character::isWhitespace)) {
            throw new RevisionResolutionException("VALIDATION_ERROR", "Git 引用格式不合法: " + ref);
        }
        return ref;
    }

    private List<String> runGit(String... arguments) {
        List<String> command = new ArrayList<>(arguments.length + 1);
        command.add("git");
        command.addAll(List.of(arguments));
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException exception) {
            throw new RevisionResolutionException("GIT_COMMAND_UNAVAILABLE", "无法启动 Git 命令");
        }

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new RevisionResolutionException("REVISION_RESOLUTION_TIMEOUT", "Git 版本解析超时");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                throw new RevisionResolutionException(
                        "REVISION_RESOLUTION_FAILED",
                        "Git 版本解析失败: " + sanitized(output)
                );
            }
            return output.lines().filter(line -> !line.isBlank()).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RevisionResolutionException("REVISION_RESOLUTION_INTERRUPTED", "Git 版本解析被中断");
        } catch (IOException exception) {
            throw new RevisionResolutionException("REVISION_RESOLUTION_FAILED", "无法读取 Git 命令输出");
        }
    }

    private String sanitized(String value) {
        String compact = value.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.length() <= 500 ? compact : compact.substring(0, 500);
    }
}
