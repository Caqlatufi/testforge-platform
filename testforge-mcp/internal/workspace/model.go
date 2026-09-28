package workspace

type Context struct {
	Root          string   `json:"root"`
	Identity      string   `json:"identity"`
	RepositoryURL string   `json:"repositoryUrl,omitempty"`
	Branch        string   `json:"branch,omitempty"`
	HeadCommit    string   `json:"headCommit"`
	Dirty         bool     `json:"dirty"`
	Manifests     []string `json:"manifests"`
	Pipelines     []string `json:"pipelines"`
	TestAssets    []string `json:"testAssets"`
}

type Error struct {
	Code    string
	Message string
}

func (e *Error) Error() string { return e.Message }
