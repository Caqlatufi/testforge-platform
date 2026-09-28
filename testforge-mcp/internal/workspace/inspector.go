package workspace

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
)

const MaxAssetBytes int64 = 20 << 20

type Inspector struct {
	root string
}

func NewInspector(root string) (*Inspector, error) {
	canonical, err := canonicalDirectory(root)
	if err != nil {
		return nil, &Error{Code: "INVALID_WORKSPACE", Message: err.Error()}
	}
	return &Inspector{root: canonical}, nil
}

func (i *Inspector) Root() string { return i.root }

func (i *Inspector) Inspect(ctx context.Context, requested string) (Context, error) {
	root, err := i.resolveDirectory(requested)
	if err != nil {
		return Context{}, err
	}
	top, err := git(ctx, root, "rev-parse", "--show-toplevel")
	if err != nil {
		return Context{}, &Error{Code: "NOT_GIT_REPOSITORY", Message: "workspace is not a Git repository"}
	}
	top, err = canonicalDirectory(top)
	if err != nil {
		return Context{}, &Error{Code: "INVALID_WORKSPACE", Message: err.Error()}
	}
	if !within(i.root, top) {
		return Context{}, &Error{Code: "WORKSPACE_OUT_OF_SCOPE", Message: "Git repository root is outside the authorized workspace"}
	}
	head, err := git(ctx, top, "rev-parse", "HEAD")
	if err != nil || len(head) != 40 {
		return Context{}, &Error{Code: "REVISION_NOT_FOUND", Message: "workspace has no resolvable HEAD commit"}
	}
	remote, _ := git(ctx, top, "remote", "get-url", "origin")
	branch, _ := git(ctx, top, "symbolic-ref", "--short", "-q", "HEAD")
	status, err := git(ctx, top, "status", "--porcelain", "--untracked-files=normal")
	if err != nil {
		return Context{}, &Error{Code: "INVALID_WORKSPACE", Message: "cannot read Git working tree status"}
	}
	manifests, pipelines, assets, err := discover(top)
	if err != nil {
		return Context{}, &Error{Code: "INVALID_WORKSPACE", Message: err.Error()}
	}
	identityHash := sha256.Sum256([]byte(strings.ToLower(strings.TrimSpace(remote)) + "\n" + strings.ToLower(top)))
	return Context{
		Root:          top,
		Identity:      "sha256:" + hex.EncodeToString(identityHash[:]),
		RepositoryURL: strings.TrimSpace(remote),
		Branch:        strings.TrimSpace(branch),
		HeadCommit:    strings.TrimSpace(head),
		Dirty:         strings.TrimSpace(status) != "",
		Manifests:     manifests,
		Pipelines:     pipelines,
		TestAssets:    assets,
	}, nil
}

func (i *Inspector) ReadFile(requested string) ([]byte, string, error) {
	if strings.TrimSpace(requested) == "" {
		return nil, "", &Error{Code: "INVALID_WORKSPACE", Message: "file path is required"}
	}
	candidate := requested
	if !filepath.IsAbs(candidate) {
		candidate = filepath.Join(i.root, candidate)
	}
	abs, err := filepath.Abs(candidate)
	if err != nil {
		return nil, "", &Error{Code: "INVALID_WORKSPACE", Message: "cannot resolve file path"}
	}
	resolved, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return nil, "", &Error{Code: "INVALID_WORKSPACE", Message: "file does not exist or cannot be resolved"}
	}
	if !within(i.root, resolved) {
		return nil, "", &Error{Code: "WORKSPACE_OUT_OF_SCOPE", Message: "file is outside the authorized workspace"}
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.Mode().IsRegular() {
		return nil, "", &Error{Code: "INVALID_WORKSPACE", Message: "path must reference a regular file"}
	}
	if info.Size() > MaxAssetBytes {
		return nil, "", &Error{Code: "INVALID_WORKSPACE", Message: fmt.Sprintf("file exceeds %d byte limit", MaxAssetBytes)}
	}
	content, err := os.ReadFile(resolved)
	if err != nil {
		return nil, "", &Error{Code: "INVALID_WORKSPACE", Message: "cannot read file"}
	}
	return content, filepath.Base(resolved), nil
}

func (i *Inspector) resolveDirectory(requested string) (string, error) {
	candidate := strings.TrimSpace(requested)
	if candidate == "" {
		candidate = i.root
	} else if !filepath.IsAbs(candidate) {
		candidate = filepath.Join(i.root, candidate)
	}
	canonical, err := canonicalDirectory(candidate)
	if err != nil {
		return "", &Error{Code: "INVALID_WORKSPACE", Message: err.Error()}
	}
	if !within(i.root, canonical) {
		return "", &Error{Code: "WORKSPACE_OUT_OF_SCOPE", Message: "requested workspace is outside the authorized root"}
	}
	return canonical, nil
}

func canonicalDirectory(path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", fmt.Errorf("cannot resolve workspace: %w", err)
	}
	resolved, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", fmt.Errorf("workspace does not exist or cannot be resolved: %w", err)
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.IsDir() {
		return "", errors.New("workspace must be an existing directory")
	}
	return filepath.Clean(resolved), nil
}

func within(root, candidate string) bool {
	rel, err := filepath.Rel(root, candidate)
	if err != nil {
		return false
	}
	return rel == "." || (rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel))
}

func git(ctx context.Context, dir string, args ...string) (string, error) {
	commandArgs := append([]string{"-C", dir}, args...)
	cmd := exec.CommandContext(ctx, "git", commandArgs...)
	cmd.Env = append(os.Environ(), "GIT_OPTIONAL_LOCKS=0")
	output, err := cmd.Output()
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(output)), nil
}

func discover(root string) ([]string, []string, []string, error) {
	manifestNames := map[string]bool{
		"go.mod": true, "pom.xml": true, "build.gradle": true, "build.gradle.kts": true,
		"settings.gradle": true, "settings.gradle.kts": true, "package.json": true,
		"pyproject.toml": true, "requirements.txt": true, "cargo.toml": true,
	}
	skipDirs := map[string]bool{".git": true, "node_modules": true, "build": true, "dist": true, "target": true, ".venv": true, "vendor": true}
	var manifests, pipelines, assets []string
	visited := 0
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if path == root {
			return nil
		}
		if entry.IsDir() {
			if skipDirs[strings.ToLower(entry.Name())] {
				return filepath.SkipDir
			}
			return nil
		}
		visited++
		if visited > 20000 {
			return errors.New("workspace discovery exceeded 20000 files")
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		base := strings.ToLower(entry.Name())
		lowerRel := strings.ToLower(rel)
		switch {
		case manifestNames[base]:
			manifests = append(manifests, rel)
		case base == "jenkinsfile" || strings.HasPrefix(lowerRel, ".github/workflows/") || strings.HasSuffix(base, ".jenkinsfile"):
			pipelines = append(pipelines, rel)
		case strings.HasSuffix(base, ".air") || strings.HasSuffix(base, ".py") || strings.HasSuffix(base, ".yaml") || strings.HasSuffix(base, ".yml"):
			if strings.Contains(lowerRel, "case") || strings.Contains(lowerRel, "test") || strings.Contains(lowerRel, "airtest") {
				assets = append(assets, rel)
			}
		}
		return nil
	})
	if err != nil {
		return nil, nil, nil, err
	}
	sort.Strings(manifests)
	sort.Strings(pipelines)
	sort.Strings(assets)
	return manifests, pipelines, assets, nil
}
