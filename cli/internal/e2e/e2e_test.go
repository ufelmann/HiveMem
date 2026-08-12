// Package e2e drives the built hivemem binary as a real user would: as a
// subprocess, against a local fake /mcp server, over the same stdin/stdout a
// shell would use. Nothing in internal/command exercises this path — those
// tests call newRootCmd().Execute() in-process, which never touches os.Args
// parsing, real file descriptors, or the real mcp-serve stdio loop.
//
// This is deliberately slow (it shells out to `go build`) and is guarded by
// HIVEMEM_CLI_E2E so it does not run on every `go test ./...`. See
// TestCLIEndToEnd for how that guard refuses to silently skip in CI.
package e2e

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"

	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// e2ePassphrase is synthetic and local to this file, exactly like
// internal/command/generate_e2e_test.go's e2ePassphrase — it never protects
// anything real, it only has to match between the subprocess that sets it and
// itself.
const e2ePassphrase = "hivemem cli e2e passphrase, not a real secret"

const e2eToken = "hivemem-e2e-token-000000000000"

func TestCLIEndToEnd(t *testing.T) {
	if os.Getenv("HIVEMEM_CLI_E2E") == "" {
		if os.Getenv("CI") != "" {
			// GitHub Actions sets CI=true for every job on every platform.
			// A skip here, reached only because a workflow edit dropped the
			// HIVEMEM_CLI_E2E=1 step env, would look exactly like coverage
			// while running nothing — the precise failure mode this test
			// exists to close. Fail loudly instead.
			t.Fatal("HIVEMEM_CLI_E2E is unset but CI is set: this workflow " +
				"step must export HIVEMEM_CLI_E2E=1, or this suite silently " +
				"stops running")
		}
		t.Skip("set HIVEMEM_CLI_E2E=1 to run the built-binary end-to-end " +
			"suite (slow: builds the binary and execs it)")
	}

	bin := buildBinary(t)

	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "writer"
	f.Tools = []json.RawMessage{json.RawMessage(
		`{"name":"note_add","description":"Add a note",` +
			`"inputSchema":{"type":"object","required":["text"],` +
			`"properties":{"text":{"type":"string","description":"note text"}}}}`,
	)}
	var gotArgsMu sync.Mutex
	var gotArgs map[string]any
	f.ToolHandler = func(name string, args map[string]any) (any, error) {
		if name == "note_add" {
			gotArgsMu.Lock()
			gotArgs = args
			gotArgsMu.Unlock()
			text, _ := args["text"].(string)
			return map[string]any{"stored": text}, nil
		}
		return map[string]any{}, nil
	}

	h := newHarness(t, bin, f.URL)

	// 1. login --token, reading the token from stdin.
	out, _, code := h.run(e2eToken+"\n", "login", "--token")
	if code != 0 {
		t.Fatalf("login: exit = %d, output:\n%s", code, out)
	}
	if !strings.Contains(out, "writer") {
		t.Fatalf("login output does not report the role:\n%s", out)
	}
	if f.ToolCallCount("wake_up") != 1 {
		t.Fatalf("wake_up called %d times during login, want 1", f.ToolCallCount("wake_up"))
	}
	if f.MethodCount("tools/list") != 1 {
		t.Fatalf("tools/list called %d times during login, want 1", f.MethodCount("tools/list"))
	}

	// 2. status: backend, role, exit 0.
	out, _, code = h.run("", "status")
	if code != 0 {
		t.Fatalf("status: exit = %d, output:\n%s", code, out)
	}
	if !strings.Contains(out, "encrypted file") {
		t.Fatalf("status does not report the encrypted-file backend:\n%s", out)
	}
	if !strings.Contains(out, "Role:     writer") {
		t.Fatalf("status does not report role writer:\n%s", out)
	}
	if !strings.Contains(out, "Status:   ok") {
		t.Fatalf("status does not report ok:\n%s", out)
	}

	// 3. tools: lists the fake server's tool.
	out, _, code = h.run("", "tools")
	if code != 0 {
		t.Fatalf("tools: exit = %d, output:\n%s", code, out)
	}
	if !strings.Contains(out, "note_add") {
		t.Fatalf("tools output does not list note_add:\n%s", out)
	}

	// 4. a generated tool subcommand with a flag: assert the fake server
	// actually received the built argument.
	out, _, code = h.run("", "note_add", "--text", "hello from e2e")
	if code != 0 {
		t.Fatalf("note_add: exit = %d, output:\n%s", code, out)
	}
	if f.ToolCallCount("note_add") != 1 {
		t.Fatalf("note_add called %d times, want 1", f.ToolCallCount("note_add"))
	}
	gotArgsMu.Lock()
	gotText := gotArgs["text"]
	gotArgsMu.Unlock()
	if gotText != "hello from e2e" {
		t.Fatalf("note_add received text = %v, want %q", gotText, "hello from e2e")
	}
	if !strings.Contains(out, "hello from e2e") {
		t.Fatalf("note_add output does not contain the tool result:\n%s", out)
	}

	// 5. mcp-serve: feed initialize and tools/call on stdin, assert
	// well-formed JSON-RPC responses on stdout.
	stdin := `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}` + "\n" +
		`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"note_add","arguments":{"text":"via mcp-serve"}}}` + "\n"
	out, errOut, code := h.run(stdin, "mcp-serve")
	if code != 0 {
		t.Fatalf("mcp-serve: exit = %d, stdout:\n%s\nstderr:\n%s", code, out, errOut)
	}
	responses := parseJSONRPCLines(t, out)
	if len(responses) != 2 {
		t.Fatalf("mcp-serve produced %d response lines, want 2:\n%s", len(responses), out)
	}
	initResp, ok := responses["1"]
	if !ok {
		t.Fatalf("no response for id 1 (initialize):\n%s", out)
	}
	if initResp["error"] != nil {
		t.Fatalf("initialize response carries an error: %v", initResp["error"])
	}
	// The bridge passes the server's response through untouched (see
	// bridge/proxy.go's normalize, which only ever rewrites "id"), so
	// initResp["result"] is exactly what fakemcp.go's initialize handler
	// returns: {"protocolVersion": ..., "serverInfo": {...}}. A mutation that
	// dropped or emptied "result" while leaving "error":null in place would
	// pass the error-only check above undetected; assert the field itself.
	initResult, ok := initResp["result"].(map[string]any)
	if !ok {
		t.Fatalf("initialize response has no result object:\n%s", out)
	}
	if initResult["protocolVersion"] != "2025-06-18" {
		t.Fatalf("initialize result missing protocolVersion, got: %v", initResult)
	}
	callResp, ok := responses["2"]
	if !ok {
		t.Fatalf("no response for id 2 (tools/call):\n%s", out)
	}
	if callResp["error"] != nil {
		t.Fatalf("tools/call response carries an error: %v", callResp["error"])
	}
	// Same reasoning as initResp above: the tool-result envelope wraps the
	// fake server's raw note_add response ({"stored":"via mcp-serve"}) as a
	// text content block (fakemcp.go's toolText/handleToolCall), passed
	// through unparsed by the bridge (proxy.go's normalize). Marshal the
	// result back out and look for the tool's own return value, so a
	// mutation that empties/truncates result.content is caught even though
	// the envelope and error:null are untouched.
	callResultBlob, err := json.Marshal(callResp["result"])
	if err != nil {
		t.Fatalf("marshal tools/call result: %v", err)
	}
	if !strings.Contains(string(callResultBlob), "via mcp-serve") {
		t.Fatalf("tools/call result does not contain the tool's returned text:\n%s", callResultBlob)
	}
	if f.ToolCallCount("note_add") != 2 {
		t.Fatalf("note_add called %d times after mcp-serve, want 2", f.ToolCallCount("note_add"))
	}

	// 6. logout: the credential is gone afterwards.
	out, _, code = h.run("", "logout")
	if code != 0 {
		t.Fatalf("logout: exit = %d, output:\n%s", code, out)
	}
	out, _, code = h.run("", "status")
	if code != 3 {
		t.Fatalf("status after logout: exit = %d, want 3, output:\n%s", code, out)
	}
	// Exact line, not a bare substring match: fixed.go's runStatus has two
	// distinct "not logged in" messages that both exit 3 — the plain
	// absent-credential case ("Status:   not logged in") and a backend
	// mismatch case ("Status:   not logged in here — a credential for this
	// profile exists in the %s backend"), which also contains the shorter
	// string as a prefix. A loose Contains check would pass if the wrong
	// branch fired (e.g. a stray credential in another backend under the
	// same profile). See internal/command/execute_test.go for the same exact
	// string used at the unit level.
	if !strings.Contains(out, "Status:   not logged in\n") {
		t.Fatalf("status after logout does not report the exact not-logged-in line:\n%s", out)
	}
}

// parseJSONRPCLines decodes each non-empty line of out as a JSON-RPC
// response, indexed by its id (as the raw JSON text of the id field, since a
// numeric id decodes to float64 and this test only needs equality against the
// literal "1"/"2" this test sent).
func parseJSONRPCLines(t *testing.T, out string) map[string]map[string]any {
	t.Helper()
	responses := map[string]map[string]any{}
	for _, line := range strings.Split(strings.TrimRight(out, "\n"), "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		var msg map[string]any
		if err := json.Unmarshal([]byte(line), &msg); err != nil {
			t.Fatalf("mcp-serve stdout line is not valid JSON: %v\nline: %s", err, line)
		}
		if msg["jsonrpc"] != "2.0" {
			t.Fatalf("mcp-serve response missing jsonrpc:2.0: %s", line)
		}
		id, ok := msg["id"]
		if !ok {
			t.Fatalf("mcp-serve response missing id: %s", line)
		}
		responses[fmt.Sprintf("%v", id)] = msg
	}
	return responses
}

// buildBinary compiles the CLI exactly the way `make build` does (see
// cli/Makefile) into a fresh t.TempDir(), and returns its path.
func buildBinary(t *testing.T) string {
	t.Helper()
	name := "hivemem"
	if runtime.GOOS == "windows" {
		name += ".exe"
	}
	bin := filepath.Join(t.TempDir(), name)

	cmd := exec.Command("go", "build", "-ldflags", "-s -w", "-o", bin, ".")
	cmd.Dir = moduleRoot(t)
	cmd.Env = append(os.Environ(), "CGO_ENABLED=0")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("go build failed: %v\n%s", err, out)
	}
	return bin
}

// moduleRoot returns cli/, derived from this file's own path so the build
// works regardless of the working directory `go test` was invoked from.
func moduleRoot(t *testing.T) string {
	t.Helper()
	_, file, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("runtime.Caller(0) failed")
	}
	// This file is cli/internal/e2e/e2e_test.go.
	return filepath.Join(filepath.Dir(file), "..", "..")
}

// harness execs the built binary with an isolated environment: a config/data
// directory pair the real user profile never sees, on every platform,
// including the two Windows env vars XDG_CONFIG_HOME/XDG_DATA_HOME do not
// override (APPDATA/LOCALAPPDATA are still set, in case any future code path
// reads them directly instead of through internal/config/paths.go).
type harness struct {
	t   *testing.T
	bin string
	env []string
}

func newHarness(t *testing.T, bin, serverURL string) *harness {
	t.Helper()
	configDir := t.TempDir()
	dataDir := t.TempDir()
	env := append(os.Environ(),
		"XDG_CONFIG_HOME="+configDir,
		"XDG_DATA_HOME="+dataDir,
		"APPDATA="+configDir,
		"LOCALAPPDATA="+dataDir,
		"DBUS_SESSION_BUS_ADDRESS=",
		"HIVEMEM_PASSPHRASE="+e2ePassphrase,
		// Forces the encrypted-file backend on every platform, including
		// Windows, where Credential Manager is otherwise unconditionally
		// available and is NOT sandboxed by the four variables above — see
		// internal/command/root.go's keystoreBackendOverride doc comment.
		"HIVEMEM_E2E_FORCE_BACKEND=encfile",
		"HIVEMEM_SERVER="+serverURL,
	)
	return &harness{t: t, bin: bin, env: env}
}

// run execs the binary with args, feeding stdin (empty string means no
// input), and returns stdout, stderr, and the process exit code.
func (h *harness) run(stdin string, args ...string) (stdout, stderr string, exitCode int) {
	h.t.Helper()
	cmd := exec.Command(h.bin, args...)
	cmd.Env = h.env
	cmd.Stdin = strings.NewReader(stdin)
	var outBuf, errBuf bytes.Buffer
	cmd.Stdout = &outBuf
	cmd.Stderr = &errBuf

	err := cmd.Run()
	if err == nil {
		return outBuf.String(), errBuf.String(), 0
	}
	var exitErr *exec.ExitError
	if errors.As(err, &exitErr) {
		return outBuf.String(), errBuf.String(), exitErr.ExitCode()
	}
	h.t.Fatalf("exec %s %v: %v\nstdout:\n%s\nstderr:\n%s",
		h.bin, args, err, outBuf.String(), errBuf.String())
	return "", "", -1
}
