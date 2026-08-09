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

// A denied tool must surface as -32003/403, not as a transport error, so the
// client's IsToolNotPermitted() discrimination is actually exercised.
func TestCallToolOnDeniedToolIsToolNotPermitted(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.DenyTool("add_cell")

	_, err := newClient(t, f.URL).CallTool(context.Background(), "add_cell", map[string]any{})
	if err == nil {
		t.Fatal("CallTool succeeded, want a denial error")
	}
	mcpErr, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error: %v", err, err)
	}
	if mcpErr.Code != -32003 {
		t.Fatalf("Code = %d, want -32003", mcpErr.Code)
	}
	if mcpErr.HTTPStatus != http.StatusForbidden {
		t.Fatalf("HTTPStatus = %d, want %d", mcpErr.HTTPStatus, http.StatusForbidden)
	}
	if !mcpErr.IsToolNotPermitted() {
		t.Fatal("IsToolNotPermitted() = false, want true")
	}
	if got := ExitCodeFor(mcpErr); got != 3 {
		t.Fatalf("ExitCodeFor = %d, want 3", got)
	}
}

// A generic 403 (the server's non-MCP path — no jsonrpc member, no -32003)
// must NOT be mistaken for a tool-not-permitted denial. Without this test,
// a client predicate as loose as "HTTPStatus == 403" would still pass
// TestCallToolOnDeniedToolIsToolNotPermitted, because the fake's DeniedTools
// response always pairs 403 with -32003 and the exact prefix.
func TestGenericForbiddenIsNotToolNotPermitted(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.ForceStatus = http.StatusForbidden
	f.ForceBody = `{"timestamp":"2026-08-09T00:00:00Z","status":403,"error":"Forbidden"}`

	_, err := newClient(t, f.URL).CallTool(context.Background(), "add_cell", map[string]any{})
	if err == nil {
		t.Fatal("CallTool succeeded, want an error")
	}
	genericErr, ok := err.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error: %v", err, err)
	}
	if genericErr.Code != 0 {
		t.Fatalf("Code = %d, want 0 (no jsonrpc error member in a generic 403)", genericErr.Code)
	}
	if genericErr.IsToolNotPermitted() {
		t.Fatal("IsToolNotPermitted() = true, want false: a bare HTTP 403 is not a -32003 denial")
	}

	f2 := testsupport.NewFakeMCP()
	defer f2.Close()
	f2.DenyTool("add_cell")

	_, err2 := newClient(t, f2.URL).CallTool(context.Background(), "add_cell", map[string]any{})
	deniedErr, ok := err2.(*Error)
	if !ok {
		t.Fatalf("error type = %T, want *Error: %v", err2, err2)
	}
	if !deniedErr.IsToolNotPermitted() {
		t.Fatal("IsToolNotPermitted() = false, want true: a genuine -32003 denial must be recognised")
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
