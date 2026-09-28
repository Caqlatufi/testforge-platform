package result

type Error struct {
	Code      string `json:"code"`
	Message   string `json:"message"`
	Retryable bool   `json:"retryable"`
	Status    int    `json:"status,omitempty"`
	TraceID   string `json:"traceId,omitempty"`
	Details   any    `json:"details,omitempty"`
}

type OperationResult struct {
	OK          bool     `json:"ok"`
	Operation   string   `json:"operation"`
	Status      string   `json:"status"`
	Summary     string   `json:"summary"`
	ProjectID   string   `json:"projectId,omitempty"`
	TestJobID   string   `json:"testJobId,omitempty"`
	RunID       string   `json:"runId,omitempty"`
	RequestKey  string   `json:"requestKey,omitempty"`
	Data        any      `json:"data,omitempty"`
	Warnings    []string `json:"warnings"`
	NextActions []string `json:"nextActions"`
	Error       *Error   `json:"error,omitempty"`
}

func Success(operation, status, summary string, data any) OperationResult {
	return OperationResult{
		OK:          true,
		Operation:   operation,
		Status:      status,
		Summary:     summary,
		Data:        data,
		Warnings:    []string{},
		NextActions: []string{},
	}
}

func Failure(operation, code, message string, retryable bool) OperationResult {
	return OperationResult{
		OK:          false,
		Operation:   operation,
		Status:      "FAILED",
		Summary:     message,
		Warnings:    []string{},
		NextActions: []string{},
		Error:       &Error{Code: code, Message: message, Retryable: retryable},
	}
}
