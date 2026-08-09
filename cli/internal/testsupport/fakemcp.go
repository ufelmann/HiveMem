// Package testsupport provides fakes that mirror the real HiveMem server's
// semantics. Where the real server is lenient, the fake must be lenient too:
// a stricter fake would let the suite lean on validation that does not exist.
package testsupport

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sync"
)

// FakeMCP is an httptest-backed /mcp endpoint.
type FakeMCP struct {
	*httptest.Server

	mu sync.Mutex
	// Calls records every JSON-RPC method received, in order.
	Calls []string
	// Tools is returned by tools/list.
	Tools []json.RawMessage
	// Role is returned by the wake_up tool.
	Role string
	// ToolHandler, if set, answers tools/call. Returning a nil result and a
	// nil error produces an empty successful result.
	ToolHandler func(name string, args map[string]any) (any, error)
	// ForceStatus, if non-zero, is returned for every request with Body as-is.
	ForceStatus int
	// ForceBody is the raw body sent when ForceStatus is set.
	ForceBody string
	// UnknownKeys records argument keys the caller sent that the tool schema
	// does not declare. The fake IGNORES them, mirroring the real server.
	UnknownKeys []string
	// KnownKeys declares the schema's properties for the unknown-key recorder.
	KnownKeys map[string][]string
	// DeniedTools holds the names DenyTool marked as not permitted.
	DeniedTools map[string]bool
}

// NewFakeMCP starts a fake server. Close it with Close().
func NewFakeMCP() *FakeMCP {
	f := &FakeMCP{Role: "writer", KnownKeys: map[string][]string{}}
	f.Server = httptest.NewServer(http.HandlerFunc(f.handle))
	return f
}

// MethodCount returns how often a JSON-RPC method was called.
func (f *FakeMCP) MethodCount(method string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, c := range f.Calls {
		if c == method {
			n++
		}
	}
	return n
}

// ToolCallCount returns how often a specific tool was called.
func (f *FakeMCP) ToolCallCount(tool string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	n := 0
	for _, c := range f.Calls {
		if c == "tools/call:"+tool {
			n++
		}
	}
	return n
}

func (f *FakeMCP) handle(w http.ResponseWriter, r *http.Request) {
	if f.ForceStatus != 0 {
		w.WriteHeader(f.ForceStatus)
		_, _ = w.Write([]byte(f.ForceBody))
		return
	}

	var req struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Method  string          `json:"method"`
		Params  json.RawMessage `json:"params"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, 200, map[string]any{
			"jsonrpc": "2.0", "id": nil,
			"error": map[string]any{"code": -32600, "message": "Invalid Request: " + err.Error()},
		})
		return
	}

	f.mu.Lock()
	f.Calls = append(f.Calls, req.Method)
	f.mu.Unlock()

	switch req.Method {
	case "initialize":
		writeJSON(w, 200, result(req.ID, map[string]any{
			"protocolVersion": "2025-06-18",
			"serverInfo":      map[string]any{"name": "hivemem", "version": "4.0.0"},
		}))
	case "tools/list":
		writeJSON(w, 200, result(req.ID, map[string]any{"tools": f.Tools}))
	case "tools/call":
		f.handleToolCall(w, req.ID, req.Params)
	default:
		writeJSON(w, 200, map[string]any{
			"jsonrpc": "2.0", "id": req.ID,
			"error": map[string]any{"code": -32601, "message": "Method not found: " + req.Method},
		})
	}
}

func (f *FakeMCP) handleToolCall(w http.ResponseWriter, id json.RawMessage, params json.RawMessage) {
	var p struct {
		Name      string         `json:"name"`
		Arguments map[string]any `json:"arguments"`
	}
	_ = json.Unmarshal(params, &p)

	f.mu.Lock()
	f.Calls = append(f.Calls, "tools/call:"+p.Name)
	if known, ok := f.KnownKeys[p.Name]; ok {
		for k := range p.Arguments {
			if !contains(known, k) {
				f.UnknownKeys = append(f.UnknownKeys, k)
			}
		}
	}
	denied := f.DeniedTools[p.Name]
	f.mu.Unlock()

	if denied {
		// Mirrors ToolCallDispatcher.java:58-60: the permission gate runs
		// before handler resolution and answers -32003 at HTTP 403.
		writeJSON(w, http.StatusForbidden, map[string]any{
			"jsonrpc": "2.0", "id": id,
			"error": map[string]any{"code": -32003, "message": "Tool not permitted: " + p.Name},
		})
		return
	}

	if p.Name == "wake_up" {
		writeJSON(w, 200, toolText(id, `{"role":"`+f.Role+`"}`))
		return
	}
	if f.ToolHandler == nil {
		writeJSON(w, 200, toolText(id, "{}"))
		return
	}
	out, err := f.ToolHandler(p.Name, p.Arguments)
	if err != nil {
		// Execution failures are a SUCCESSFUL result with isError, exactly as
		// ToolCallDispatcher.java:91-96 does.
		writeJSON(w, 200, result(id, map[string]any{
			"content": []any{map[string]any{"type": "text", "text": err.Error()}},
			"isError": true,
		}))
		return
	}
	blob, _ := json.Marshal(out)
	writeJSON(w, 200, toolText(id, string(blob)))
}

// DenyTool makes the named tool answer -32003 / HTTP 403, which is what the
// real server returns for a tool that is unknown OR not permitted.
func (f *FakeMCP) DenyTool(name string) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.DeniedTools == nil {
		f.DeniedTools = map[string]bool{}
	}
	f.DeniedTools[name] = true
}

func result(id json.RawMessage, payload any) map[string]any {
	return map[string]any{"jsonrpc": "2.0", "id": id, "result": payload}
}

func toolText(id json.RawMessage, text string) map[string]any {
	return result(id, map[string]any{
		"content": []any{map[string]any{"type": "text", "text": text}},
		"isError": false,
	})
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func contains(hay []string, needle string) bool {
	for _, h := range hay {
		if h == needle {
			return true
		}
	}
	return false
}
