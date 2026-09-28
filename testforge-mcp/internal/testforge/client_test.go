package testforge

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

func TestClientRequestUnwrapsDataAndSendsToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if request.Header.Get("Authorization") != "Bearer secret" {
			t.Errorf("missing token: %q", request.Header.Get("Authorization"))
		}
		if request.URL.Query().Get("projectId") != "p-1" {
			t.Errorf("missing query: %s", request.URL.RawQuery)
		}
		response.Header().Set("Content-Type", "application/json")
		io.WriteString(response, `{"code":"OK","message":"ok","traceId":"0123456789abcdef0123456789abcdef","data":{"id":"p-1"}}`)
	}))
	defer server.Close()
	client := NewClient(server.URL, "secret", time.Second)
	data, err := client.Request(context.Background(), http.MethodGet, "/api/v1/projects/p-1", url.Values{"projectId": []string{"p-1"}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if data.(map[string]any)["id"] != "p-1" {
		t.Fatalf("unexpected data: %#v", data)
	}
}

func TestClientReturnsStructuredAPIError(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, _ *http.Request) {
		response.WriteHeader(http.StatusConflict)
		io.WriteString(response, `{"code":"STATE_CONFLICT","message":"conflict","traceId":"trace","details":{"state":"ACTIVE"}}`)
	}))
	defer server.Close()
	client := NewClient(server.URL, "", time.Second)
	_, err := client.Request(context.Background(), http.MethodPost, "/conflict", nil, map[string]any{})
	apiError, ok := err.(*Error)
	if !ok || apiError.Status != 409 || apiError.Code != "STATE_CONFLICT" || apiError.TraceID != "trace" {
		t.Fatalf("unexpected error: %#v", err)
	}
}

func TestClientUploadsMultipartAsset(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		if !strings.HasPrefix(request.Header.Get("Content-Type"), "multipart/form-data;") {
			t.Fatalf("unexpected content type %q", request.Header.Get("Content-Type"))
		}
		file, header, err := request.FormFile("file")
		if err != nil {
			t.Fatal(err)
		}
		defer file.Close()
		content, _ := io.ReadAll(file)
		if header.Filename != "case.py" || string(content) != "print('ok')" {
			t.Fatalf("unexpected upload %q %q", header.Filename, content)
		}
		json.NewEncoder(response).Encode(map[string]any{"code": "OK", "message": "ok", "traceId": "trace", "data": map[string]any{"id": "asset"}})
	}))
	defer server.Close()
	client := NewClient(server.URL, "", time.Second)
	data, err := client.Upload(context.Background(), "/assets", "case.py", []byte("print('ok')"))
	if err != nil || data.(map[string]any)["id"] != "asset" {
		t.Fatalf("unexpected result %#v %v", data, err)
	}
}
