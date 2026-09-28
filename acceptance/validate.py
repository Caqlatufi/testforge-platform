from pathlib import Path


def main() -> int:
    acceptance_root = Path(__file__).resolve().parent
    repository_root = acceptance_root.parent
    required = (
        acceptance_root / "README.md",
        acceptance_root / "bruno" / "README.md",
        acceptance_root / "reliability" / "README.md",
        repository_root / "Jenkinsfile",
    )
    missing = [str(path.relative_to(repository_root)) for path in required if not path.is_file()]
    if missing:
        print("Acceptance scaffold is incomplete: " + ", ".join(missing))
        return 1
    if (repository_root.joinpath(".github", "workflows", "testforge-ci.yml").exists()):
        print("Cross-project root workflow must not exist: .github/workflows/testforge-ci.yml")
        return 1

    init_script = repository_root.joinpath(
        "infra", "jenkins", "init.groovy.d", "01-testforge-job.groovy"
    )
    compose = repository_root.joinpath(
        "infra", "compose.jenkins.yaml"
    ).read_text(encoding="utf-8")
    platform_pipeline = repository_root.joinpath("Jenkinsfile").read_text(encoding="utf-8")
    contracts = {
        "Platform must not create project-specific Jenkins jobs": not init_script.exists(),
        "Jenkins Compose must not mount the business repository":
            "/workspace/testforge-demo" not in compose and "TESTFORGE_DEMO_REPOSITORY" not in compose,
        "Platform must require a full commit SHA":
            "[0-9a-fA-F]{40}" in platform_pipeline,
        "Platform must check out the requested immutable commit":
            "git checkout --detach" in platform_pipeline,
        "Platform must expose the initialization convention":
            "INITIALIZE_ENVIRONMENT" in platform_pipeline
            and "stage('Initialize Environment')" in platform_pipeline,
    }
    invalid = [message for message, valid in contracts.items() if not valid]
    if invalid:
        print("Pipeline contract validation failed: " + "; ".join(invalid))
        return 1

    print("Acceptance scaffold and project Pipeline contracts passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
