package config

import (
	"path/filepath"
	"testing"
	"time"
)

func TestLoadUsesFlagsAndValidatesURL(t *testing.T) {
	t.Setenv("TESTFORGE_URL", "")
	t.Setenv("TESTFORGE_TOKEN", "")
	t.Setenv("TESTFORGE_WORKSPACE", "")
	t.Setenv("TESTFORGE_BINDINGS", "")
	t.Setenv("TESTFORGE_TIMEOUT", "")
	root := t.TempDir()
	cfg, err := Load([]string{"--url", "http://localhost:8081/", "--workspace", root, "--bindings", filepath.Join(root, "bindings.json"), "--timeout", "2.5"}, BuildInfo{Version: "test"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.BaseURL != "http://localhost:8081" {
		t.Fatalf("unexpected base URL %q", cfg.BaseURL)
	}
	if cfg.Timeout != 2500*time.Millisecond {
		t.Fatalf("unexpected timeout %v", cfg.Timeout)
	}
	if _, err := Load([]string{"--url", "localhost:8081", "--workspace", root}, BuildInfo{}); err == nil {
		t.Fatal("expected invalid URL error")
	}
}
