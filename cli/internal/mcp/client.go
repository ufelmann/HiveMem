// Package mcp is the JSON-RPC client for the HiveMem /mcp endpoint.
package mcp

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/visterion/hivemem/cli/internal/httplog"
	"github.com/visterion/hivemem/cli/internal/redact"
)

// ProtocolVersion is the version this client speaks. 2025-06-18 removed
// JSON-RPC batching, which this client never sends.
const ProtocolVersion = "2025-06-18"

// Timeouts bounds every request. Go's default http.Client has none, so a
// wedged connection would hang the caller forever.
type Timeouts struct {
	ToolCall time.Duration
	Metadata time.Duration
}

// DefaultTimeouts returns 30 s for tool calls and 10 s for everything else.
func DefaultTimeouts() Timeouts {
	return Timeouts{ToolCall: 30 * time.Second, Metadata: 10 * time.Second}
}

// Client talks JSON-RPC to one server with one bearer token.
type Client struct {
	serverURL string
	token     string
	timeouts  Timeouts
	http      *http.Client
}

// New returns a client. The token is registered with the redactor immediately.
func New(serverURL, token string, t Timeouts) *Client {
	redact.Register(token)
	return &Client{
		serverURL: strings.TrimRight(serverURL, "/"),
		token:     token,
		timeouts:  t,
		http:      &http.Client{},
	}
}

// InitializeResult is the handshake response.
type InitializeResult struct {
	ProtocolVersion string `json:"protocolVersion"`
	ServerInfo      struct {
		Name    string `json:"name"`
		Version string `json:"version"`
	} `json:"serverInfo"`
}

// WakeUpResult carries the effective role. tools/list does not return one, so
// this is the only source.
type WakeUpResult struct {
	Identity string `json:"identity"`
	Role     string `json:"role"`
}

// ToolResult is one tools/call response.
type ToolResult struct {
	Text    string
	IsError bool
	Raw     json.RawMessage
}

type rpcResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  json.RawMessage `json:"result"`
	Error   *struct {
		Code    int    `json:"code"`
		Message string `json:"message"`
	} `json:"error"`
}

// Initialize performs the MCP handshake.
func (c *Client) Initialize(ctx context.Context) (*InitializeResult, error) {
	raw, err := c.call(ctx, c.timeouts.Metadata, "initialize", map[string]any{
		"protocolVersion": ProtocolVersion,
		"clientInfo":      map[string]any{"name": "hivemem-cli", "version": "1"},
		"capabilities":    map[string]any{},
	})
	if err != nil {
		return nil, err
	}
	var out InitializeResult
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("decode initialize result: %w", err)
	}
	return &out, nil
}

// ListTools returns the tool definitions visible to this token's role.
func (c *Client) ListTools(ctx context.Context) ([]json.RawMessage, error) {
	raw, err := c.call(ctx, c.timeouts.Metadata, "tools/list", map[string]any{})
	if err != nil {
		return nil, err
	}
	var out struct {
		Tools []json.RawMessage `json:"tools"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("decode tools/list result: %w", err)
	}
	return out.Tools, nil
}

// CallTool invokes one tool.
func (c *Client) CallTool(ctx context.Context, name string, args map[string]any) (*ToolResult, error) {
	if args == nil {
		args = map[string]any{}
	}
	raw, err := c.call(ctx, c.timeouts.ToolCall, "tools/call", map[string]any{
		"name": name, "arguments": args,
	})
	if err != nil {
		return nil, err
	}
	var out struct {
		Content []struct {
			Type string `json:"type"`
			Text string `json:"text"`
		} `json:"content"`
		IsError bool `json:"isError"`
	}
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("decode tool result: %w", err)
	}
	var sb strings.Builder
	for _, blk := range out.Content {
		sb.WriteString(blk.Text)
	}
	return &ToolResult{Text: sb.String(), IsError: out.IsError, Raw: raw}, nil
}

// WakeUp returns the effective role for this credential.
func (c *Client) WakeUp(ctx context.Context) (*WakeUpResult, error) {
	res, err := c.CallTool(ctx, "wake_up", map[string]any{})
	if err != nil {
		return nil, err
	}
	if res.IsError {
		return nil, &Error{Message: res.Text, HTTPStatus: http.StatusOK}
	}
	var out WakeUpResult
	if err := json.Unmarshal([]byte(res.Text), &out); err != nil {
		return nil, fmt.Errorf("decode wake_up result: %w", err)
	}
	return &out, nil
}

func (c *Client) call(ctx context.Context, timeout time.Duration, method string, params any) (json.RawMessage, error) {
	body, err := json.Marshal(map[string]any{
		"jsonrpc": "2.0", "id": 1, "method": method, "params": params,
	})
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.serverURL+"/mcp", bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	// Content-Type is mandatory: without it Spring's @RequestBody binding
	// answers 415 before the controller runs.
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("Authorization", "Bearer "+c.token)

	httplog.Request(http.MethodPost, c.serverURL+"/mcp", body)

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, redact.Wrap(err)
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, redact.Wrap(err)
	}
	httplog.Response(resp.StatusCode, raw)

	// Read from the header, not the body: a 429 from AuthFilter goes through
	// response.sendError, whose body carries no wait time at all.
	retryAfter := resp.Header.Get("Retry-After")

	var rpc rpcResponse
	if jsonErr := json.Unmarshal(raw, &rpc); jsonErr != nil || rpc.JSONRPC == "" {
		// Not a JSON-RPC body: a Spring error page, an HTML proxy error, etc.
		return nil, &Error{HTTPStatus: resp.StatusCode, Message: summarize(raw),
			RetryAfter: retryAfter}
	}
	if rpc.Error != nil {
		return nil, &Error{Code: rpc.Error.Code, Message: rpc.Error.Message,
			HTTPStatus: resp.StatusCode, RetryAfter: retryAfter}
	}
	if resp.StatusCode >= 400 {
		return nil, &Error{HTTPStatus: resp.StatusCode, Message: summarize(raw),
			RetryAfter: retryAfter}
	}
	return rpc.Result, nil
}

func summarize(body []byte) string {
	s := redact.Apply(strings.TrimSpace(string(body)))
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	if s == "" {
		s = "(empty response body)"
	}
	return s
}
