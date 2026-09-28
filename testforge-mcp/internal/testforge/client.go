package testforge

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"path/filepath"
	"strings"
	"time"
)

const maxResponseBytes = 16 << 20

type API interface {
	Request(ctx context.Context, method, path string, query url.Values, body any) (any, error)
	Upload(ctx context.Context, path, fileName string, content []byte) (any, error)
}

type Client struct {
	baseURL string
	token   string
	http    *http.Client
}

type Error struct {
	Status  int
	Code    string
	Message string
	TraceID string
	Details any
}

func (e *Error) Error() string { return e.Message }

type envelope struct {
	Code    string          `json:"code"`
	Message string          `json:"message"`
	TraceID string          `json:"traceId"`
	Data    json.RawMessage `json:"data"`
	Details any             `json:"details"`
}

func NewClient(baseURL, token string, timeout time.Duration) *Client {
	return &Client{
		baseURL: strings.TrimRight(baseURL, "/"),
		token:   strings.TrimSpace(token),
		http:    &http.Client{Timeout: timeout},
	}
}

func (c *Client) Request(ctx context.Context, method, path string, query url.Values, body any) (any, error) {
	var reader io.Reader
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("encode request: %w", err)
		}
		reader = bytes.NewReader(encoded)
	}
	requestURL := c.baseURL + path
	if len(query) != 0 {
		requestURL += "?" + query.Encode()
	}
	req, err := http.NewRequestWithContext(ctx, method, requestURL, reader)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	c.authorize(req)
	return c.do(req)
}

func (c *Client) Upload(ctx context.Context, path, fileName string, content []byte) (any, error) {
	var body bytes.Buffer
	writer := multipart.NewWriter(&body)
	part, err := writer.CreateFormFile("file", filepath.Base(fileName))
	if err != nil {
		return nil, fmt.Errorf("create multipart file: %w", err)
	}
	if _, err := part.Write(content); err != nil {
		return nil, fmt.Errorf("write multipart file: %w", err)
	}
	if err := writer.Close(); err != nil {
		return nil, fmt.Errorf("close multipart request: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+path, &body)
	if err != nil {
		return nil, fmt.Errorf("create upload request: %w", err)
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", writer.FormDataContentType())
	c.authorize(req)
	return c.do(req)
}

func (c *Client) authorize(req *http.Request) {
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
}

func (c *Client) do(req *http.Request) (any, error) {
	response, err := c.http.Do(req)
	if err != nil {
		if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
			return nil, fmt.Errorf("request cancelled or timed out: %w", err)
		}
		return nil, fmt.Errorf("cannot connect to TestForge: %w", err)
	}
	defer response.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read TestForge response: %w", err)
	}
	if len(raw) > maxResponseBytes {
		return nil, errors.New("TestForge response exceeds 16 MiB")
	}
	if len(raw) == 0 {
		return nil, errors.New("TestForge returned an empty response")
	}
	var wrapped envelope
	if err := json.Unmarshal(raw, &wrapped); err != nil {
		return nil, errors.New("TestForge returned non-JSON or incompatible JSON")
	}
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		message := strings.TrimSpace(wrapped.Message)
		if message == "" {
			message = response.Status
		}
		code := strings.TrimSpace(wrapped.Code)
		if code == "" {
			code = "HTTP_ERROR"
		}
		return nil, &Error{Status: response.StatusCode, Code: code, Message: message, TraceID: wrapped.TraceID, Details: wrapped.Details}
	}
	if wrapped.Data == nil {
		return nil, errors.New("TestForge response is missing data")
	}
	var data any
	decoder := json.NewDecoder(bytes.NewReader(wrapped.Data))
	decoder.UseNumber()
	if err := decoder.Decode(&data); err != nil {
		return nil, errors.New("TestForge data field is incompatible JSON")
	}
	return data, nil
}
