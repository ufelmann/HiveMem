package mcp

import (
	"fmt"
	"net/http"
	"testing"
)

func TestExitCodeMapping(t *testing.T) {
	cases := []struct {
		name string
		err  *Error
		want int
	}{
		{"tool not permitted", &Error{Code: -32003, Message: "Tool not permitted: add_cell", HTTPStatus: http.StatusForbidden}, 3},
		{"unknown tool", &Error{Code: -32602, Message: "Unknown tool: nope", HTTPStatus: http.StatusOK}, 2},
		{"invalid params", &Error{Code: -32602, Message: "limit must be positive", HTTPStatus: http.StatusOK}, 2},
		{"missing principal 400", &Error{Code: -32602, Message: "Missing authenticated principal", HTTPStatus: http.StatusBadRequest}, 3},
		{"embedding gate", &Error{Code: -32603, Message: "Embedding re-encoding in progress (12%)", HTTPStatus: http.StatusServiceUnavailable}, 4},
		{"unauthorized", &Error{HTTPStatus: http.StatusUnauthorized}, 3},
		{"rate limited", &Error{HTTPStatus: http.StatusTooManyRequests}, 4},
		{"server error", &Error{HTTPStatus: http.StatusInternalServerError}, 4},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := ExitCodeFor(tc.err); got != tc.want {
				t.Fatalf("exit code = %d, want %d", got, tc.want)
			}
		})
	}
}

// The Unknown tool: prefix is the only reliable discriminator between the two
// -32602 producers; argument validation must NOT trigger a cache refresh.
func TestUnknownToolIsDistinguishedByPrefix(t *testing.T) {
	unknown := &Error{Code: -32602, Message: "Unknown tool: frobnicate"}
	if !unknown.IsUnknownTool() {
		t.Fatal("the Unknown tool: prefix was not recognised")
	}
	args := &Error{Code: -32602, Message: "query must not be blank"}
	if args.IsUnknownTool() {
		t.Fatal("an argument-validation error must not look like an unknown tool")
	}
}

func TestToolNotPermittedIsRecognised(t *testing.T) {
	e := &Error{Code: -32003, Message: "Tool not permitted: add_cell", HTTPStatus: http.StatusForbidden}
	if !e.IsToolNotPermitted() {
		t.Fatal("-32003 was not recognised as a permission denial")
	}
}

func TestExitCodeForUnwrapsError(t *testing.T) {
	// A *Error with HTTPStatus 401 wrapped in fmt.Errorf should still map to exit code 3,
	// which happens when a login token is rejected and the error is wrapped with
	// fmt.Errorf("the token was rejected: %w", err).
	underlying := &Error{HTTPStatus: http.StatusUnauthorized, Message: "invalid token"}
	wrapped := fmt.Errorf("the token was rejected: %w", underlying)

	if got := ExitCodeFor(wrapped); got != 3 {
		t.Fatalf("wrapped 401 exit code = %d, want 3", got)
	}
}
