package binding

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type WorkspaceBinding struct {
	TestForgeURL      string    `json:"testforgeUrl"`
	WorkspaceIdentity string    `json:"workspaceIdentity"`
	ProjectID         string    `json:"projectId"`
	RepositoryURL     string    `json:"repositoryUrl,omitempty"`
	UpdatedAt         time.Time `json:"updatedAt"`
}

type document struct {
	Version  int                `json:"version"`
	Bindings []WorkspaceBinding `json:"bindings"`
}

type Store struct {
	path string
	mu   sync.RWMutex
}

func NewStore(path string) *Store { return &Store{path: path} }

func (s *Store) Get(endpoint, identity string) (WorkspaceBinding, bool, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	doc, err := s.read()
	if err != nil {
		return WorkspaceBinding{}, false, err
	}
	for _, item := range doc.Bindings {
		if same(item.TestForgeURL, endpoint) && item.WorkspaceIdentity == identity {
			return item, true, nil
		}
	}
	return WorkspaceBinding{}, false, nil
}

func (s *Store) Save(value WorkspaceBinding) error {
	if strings.TrimSpace(value.TestForgeURL) == "" || strings.TrimSpace(value.WorkspaceIdentity) == "" || strings.TrimSpace(value.ProjectID) == "" {
		return errors.New("binding requires TestForge URL, workspace identity and project ID")
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	doc, err := s.read()
	if err != nil {
		return err
	}
	value.TestForgeURL = strings.TrimRight(value.TestForgeURL, "/")
	value.UpdatedAt = time.Now().UTC()
	replaced := false
	for index := range doc.Bindings {
		if same(doc.Bindings[index].TestForgeURL, value.TestForgeURL) && doc.Bindings[index].WorkspaceIdentity == value.WorkspaceIdentity {
			doc.Bindings[index] = value
			replaced = true
			break
		}
	}
	if !replaced {
		doc.Bindings = append(doc.Bindings, value)
	}
	return s.write(doc)
}

func (s *Store) read() (document, error) {
	raw, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) {
		return document{Version: 1, Bindings: []WorkspaceBinding{}}, nil
	}
	if err != nil {
		return document{}, fmt.Errorf("read binding store: %w", err)
	}
	var doc document
	if err := json.Unmarshal(raw, &doc); err != nil || doc.Version != 1 {
		return document{}, errors.New("BINDING_STORE_INVALID: binding file is not valid version 1 JSON")
	}
	if doc.Bindings == nil {
		doc.Bindings = []WorkspaceBinding{}
	}
	return doc, nil
}

func (s *Store) write(doc document) error {
	directory := filepath.Dir(s.path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create binding directory: %w", err)
	}
	temp, err := os.CreateTemp(directory, ".bindings-*.tmp")
	if err != nil {
		return fmt.Errorf("create binding temp file: %w", err)
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if err := temp.Chmod(0o600); err != nil {
		temp.Close()
		return err
	}
	encoder := json.NewEncoder(temp)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(doc); err != nil {
		temp.Close()
		return fmt.Errorf("encode binding store: %w", err)
	}
	if err := temp.Sync(); err != nil {
		temp.Close()
		return fmt.Errorf("sync binding store: %w", err)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("close binding store: %w", err)
	}
	if err := os.Rename(tempName, s.path); err != nil {
		return fmt.Errorf("atomically replace binding store: %w", err)
	}
	return nil
}

func same(left, right string) bool {
	return strings.EqualFold(strings.TrimRight(left, "/"), strings.TrimRight(right, "/"))
}
