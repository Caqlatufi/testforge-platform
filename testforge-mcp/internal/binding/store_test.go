package binding

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
	"sync"
	"testing"
)

func TestStoreConcurrentWritesRemainValid(t *testing.T) {
	path := filepath.Join(t.TempDir(), "testforge", "bindings.json")
	store := NewStore(path)
	const writers = 50
	var wait sync.WaitGroup
	for index := 0; index < writers; index++ {
		wait.Add(1)
		go func(index int) {
			defer wait.Done()
			if err := store.Save(WorkspaceBinding{
				TestForgeURL: "http://localhost:8081", WorkspaceIdentity: "workspace-" + strconv.Itoa(index), ProjectID: "project-" + strconv.Itoa(index),
			}); err != nil {
				t.Errorf("save %d: %v", index, err)
			}
		}(index)
	}
	wait.Wait()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var saved document
	if err := json.Unmarshal(raw, &saved); err != nil {
		t.Fatalf("binding file is invalid JSON: %v", err)
	}
	if len(saved.Bindings) != writers {
		t.Fatalf("expected %d bindings, got %d", writers, len(saved.Bindings))
	}
	value, found, err := store.Get("http://localhost:8081/", "workspace-7")
	if err != nil || !found || value.ProjectID != "project-7" {
		t.Fatalf("unexpected lookup: %+v %v %v", value, found, err)
	}
}

func TestStoreDoesNotOverwriteInvalidFile(t *testing.T) {
	path := filepath.Join(t.TempDir(), "bindings.json")
	if err := os.WriteFile(path, []byte("not-json"), 0o600); err != nil {
		t.Fatal(err)
	}
	store := NewStore(path)
	if err := store.Save(WorkspaceBinding{TestForgeURL: "http://localhost", WorkspaceIdentity: "w", ProjectID: "p"}); err == nil {
		t.Fatal("expected invalid store error")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != "not-json" {
		t.Fatalf("invalid store was overwritten: %q", raw)
	}
}
