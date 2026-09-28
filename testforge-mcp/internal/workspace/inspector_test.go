package workspace

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestInspectorReadsRepositoryAndRejectsOutsidePaths(t *testing.T) {
	root := t.TempDir()
	repository := filepath.Join(root, "project")
	if err := os.MkdirAll(filepath.Join(repository, "tests"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repository, "go.mod"), []byte("module example.test/project\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repository, "Jenkinsfile"), []byte("pipeline {}\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(repository, "tests", "case.yaml"), []byte("kind: TestCase\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGit(t, repository, "init")
	runGit(t, repository, "config", "user.email", "test@example.com")
	runGit(t, repository, "config", "user.name", "Test")
	runGit(t, repository, "remote", "add", "origin", "https://example.com/acme/project.git")
	runGit(t, repository, "add", ".")
	runGit(t, repository, "commit", "-m", "initial")

	inspector, err := NewInspector(repository)
	if err != nil {
		t.Fatal(err)
	}
	facts, err := inspector.Inspect(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if facts.Dirty || len(facts.HeadCommit) != 40 || facts.RepositoryURL != "https://example.com/acme/project.git" {
		t.Fatalf("unexpected Git facts: %+v", facts)
	}
	if len(facts.Manifests) != 1 || len(facts.Pipelines) != 1 || len(facts.TestAssets) != 1 {
		t.Fatalf("unexpected discovery: %+v", facts)
	}
	outside := filepath.Join(root, "outside.txt")
	if err := os.WriteFile(outside, []byte("secret"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, _, err := inspector.ReadFile(outside); err == nil {
		t.Fatal("expected outside file to be rejected")
	} else if workspaceErr, ok := err.(*Error); !ok || workspaceErr.Code != "WORKSPACE_OUT_OF_SCOPE" {
		t.Fatalf("unexpected error: %v", err)
	}
	if _, err := inspector.Inspect(context.Background(), ".."); err == nil {
		t.Fatal("expected parent workspace to be rejected")
	}
}

func TestInspectorReportsDirtyWorkspace(t *testing.T) {
	repository := t.TempDir()
	runGit(t, repository, "init")
	runGit(t, repository, "config", "user.email", "test@example.com")
	runGit(t, repository, "config", "user.name", "Test")
	if err := os.WriteFile(filepath.Join(repository, "README.md"), []byte("initial"), 0o644); err != nil {
		t.Fatal(err)
	}
	runGit(t, repository, "add", ".")
	runGit(t, repository, "commit", "-m", "initial")
	if err := os.WriteFile(filepath.Join(repository, "README.md"), []byte("dirty"), 0o644); err != nil {
		t.Fatal(err)
	}
	inspector, err := NewInspector(repository)
	if err != nil {
		t.Fatal(err)
	}
	facts, err := inspector.Inspect(context.Background(), "")
	if err != nil {
		t.Fatal(err)
	}
	if !facts.Dirty {
		t.Fatal("expected dirty workspace")
	}
}

func TestReadFileRejectsSymlinkEscapingAuthorizedRoot(t *testing.T) {
	parent := t.TempDir()
	root := filepath.Join(parent, "authorized")
	if err := os.Mkdir(root, 0o755); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(parent, "secret.py")
	if err := os.WriteFile(outside, []byte("secret"), 0o644); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(root, "case.py")
	if err := os.Symlink(outside, link); err != nil {
		t.Skipf("symlink creation is not available: %v", err)
	}
	inspector, err := NewInspector(root)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := inspector.ReadFile(link); err == nil {
		t.Fatal("expected escaping symlink to be rejected")
	} else if workspaceErr, ok := err.(*Error); !ok || workspaceErr.Code != "WORKSPACE_OUT_OF_SCOPE" {
		t.Fatalf("unexpected error: %v", err)
	}
}

func runGit(t *testing.T, directory string, args ...string) {
	t.Helper()
	commandArgs := append([]string{"-C", directory}, args...)
	cmd := exec.Command("git", commandArgs...)
	cmd.Env = append(os.Environ(), "GIT_CONFIG_NOSYSTEM=1")
	if output, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("git %v failed: %v\n%s", args, err, output)
	}
}
