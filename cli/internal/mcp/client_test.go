package mcp

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/visterion/hivemem/cli/internal/testsupport"
)

func newClient(t *testing.T, url string) *Client {
	t.Helper()
	return New(url, "test-token-aaaaaaaaaa", DefaultTimeouts())
}

func TestListToolsReturnsTheServersTools(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{
		json.RawMessage(`{"name":"search","inputSchema":{"type":"object"}}`),
		json.RawMessage(`{"name":"add_cell","inputSchema":{"type":"object"}}`),
	}

	tools, err := newClient(t, f.URL).ListTools(context.Background())
	if err != nil {
		t.Fatalf("ListTools: %v", err)
	}
	if len(tools) != 2 {
		t.Fatalf("got %d tools, want 2", len(tools))
	}
}

func TestWakeUpReturnsTheEffectiveRole(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "reader"

	got, err := newClient(t, f.URL).WakeUp(context.Background())
	if err != nil {
		t.Fatalf("WakeUp: %v", err)
	}
	if got.Role != "reader" {
		t.Fatalf("role = %q, want reader", got.Role)
	}
}

// A failed tool call is HTTP 200 with isError:true. It must NOT look like
// success, or a wrapping script treats a failed write as a successful one.
func TestCallToolSurfacesIsErrorResults(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.ToolHandler = func(name string, args map[string]any) (any, error) {
		return nil, errString("duplicate key value violates unique constraint")
	}

	res, err := newClient(t, f.URL).CallTool(context.Background(), "add_cell", map[string]any{})
	if err != nil {
		t.Fatalf("CallTool returned a transport error, want a result: %v", err)
	}
	if !res.IsError {
		t.Fatal("isError:true was not surfaced")
	}
	if ExitCodeForResult(res) != 5 {
		t.Fatalf("exit code = %d, want 5", ExitCodeForResult(res))
	}
}

func TestCallToolSuccessIsExitZero(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.ToolHandler = func(string, map[string]any) (any, error) {
		return map[string]any{"ok": true}, nil
	}

	res, err := newClient(t, f.URL).CallTool(context.Background(), "search", map[string]any{})
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	if res.IsError {
		t.Fatal("a successful call must not be flagged isError")
	}
	if ExitCodeForResult(res) != 0 {
		t.Fatalf("exit code = %d, want 0", ExitCodeForResult(res))
	}
}

func TestAuthorizationHeaderIsSent(t *testing.T) {
	var got string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Get("Authorization")
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}`))
	}))
	defer srv.Close()

	_, _ = New(srv.URL, "token-value-bbbbbbbb", DefaultTimeouts()).ListTools(context.Background())
	if got != "Bearer token-value-bbbbbbbb" {
		t.Fatalf("Authorization = %q", got)
	}
}

type errString string

func (e errString) Error() string { return string(e) }
