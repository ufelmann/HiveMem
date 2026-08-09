package mcp

import (
	"errors"
	"fmt"
	"net/http"
	"strings"
)

// JSON-RPC codes this server actually emits.
const (
	CodeInvalidRequest = -32600
	CodeMethodNotFound = -32601
	CodeInvalidParams  = -32602
	CodeInternal       = -32603
	CodeForbidden      = -32003
)

// unknownToolPrefix is unique to McpResponse.toolNotFound and is the only
// reliable way to tell an unknown tool from an argument-validation failure,
// which shares the -32602 code.
const unknownToolPrefix = "Unknown tool:"

// notPermittedPrefix is unique to McpResponse.forbidden.
const notPermittedPrefix = "Tool not permitted:"

// Error is a JSON-RPC error together with the HTTP status that carried it.
// Both matter: -32602 means different things at HTTP 200 and HTTP 400.
type Error struct {
	Code       int
	Message    string
	HTTPStatus int
	// RetryAfter is the Retry-After header, in seconds, as the server sent it.
	// AuthFilter.java:116 sets it on every 429 it raises, and it is the only
	// way to tell "wait 40 seconds" from "wait 15 minutes" — without it a
	// rate-limit ban is indistinguishable from a broken token.
	RetryAfter string
}

func (e *Error) Error() string {
	suffix := ""
	if e.RetryAfter != "" {
		suffix = fmt.Sprintf(" (retry after %ss)", e.RetryAfter)
	}
	if e.Code != 0 {
		return fmt.Sprintf("server error %d: %s%s", e.Code, e.Message, suffix)
	}
	return fmt.Sprintf("HTTP %d: %s%s", e.HTTPStatus, e.Message, suffix)
}

// IsUnknownTool reports the defensive path: a permitted tool name with no
// registered handler. In production an unknown name is answered by
// IsToolNotPermitted instead, because the permission gate runs first.
func (e *Error) IsUnknownTool() bool {
	return e.Code == CodeInvalidParams && strings.HasPrefix(e.Message, unknownToolPrefix)
}

// IsToolNotPermitted reports the production path for both a removed tool and a
// genuine role or realm denial. They are indistinguishable at the wire level.
func (e *Error) IsToolNotPermitted() bool {
	return e.Code == CodeForbidden && strings.HasPrefix(e.Message, notPermittedPrefix)
}

// IsInvalidParams reports an argument-validation failure, which must never
// trigger a schema refresh.
func (e *Error) IsInvalidParams() bool {
	return e.Code == CodeInvalidParams && !e.IsUnknownTool()
}

// IsInternal reports -32603, which arrives inside HTTP 503 while an embedding
// re-encode runs. Its message names the progress and is worth printing.
func (e *Error) IsInternal() bool { return e.Code == CodeInternal }

// ExitCodeFor maps an error to the CLI's exit code.
func ExitCodeFor(err error) int {
	var e *Error
	if !errors.As(err, &e) {
		return 1
	}
	switch {
	case e.IsToolNotPermitted():
		return 3
	case e.IsInternal():
		return 4
	case e.IsUnknownTool(), e.IsInvalidParams():
		// A -32602 arriving with HTTP 400 is the missing-principal case, which
		// is an auth problem rather than a usage one.
		if e.HTTPStatus == http.StatusBadRequest {
			return 3
		}
		return 2
	}
	switch e.HTTPStatus {
	case http.StatusUnauthorized, http.StatusForbidden:
		return 3
	case http.StatusTooManyRequests,
		http.StatusInternalServerError, http.StatusBadGateway,
		http.StatusServiceUnavailable, http.StatusGatewayTimeout:
		return 4
	}
	return 1
}

// ExitCodeForResult maps a tool result to an exit code. A failed tool call is
// an HTTP 200 with isError:true, so without this it would exit 0.
func ExitCodeForResult(r *ToolResult) int {
	if r != nil && r.IsError {
		return 5
	}
	return 0
}
