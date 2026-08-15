package command

import (
	"bytes"
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// mcp-serve builds its own keystore/cache/manager rather than going through
// resolveDeps (its stdin doubles as the JSON-RPC transport, so it must never
// prompt), so these tests set up the same environment resolveDeps's tests do
// and drive the command end to end through cobra.
func newServeTestEnv(t *testing.T, serverURL string) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	t.Setenv("HIVEMEM_SERVER", serverURL)
	pinEncFileBackend(t)

	saved := opts.server
	opts.server = ""
	t.Cleanup(func() { opts.server = saved })
}

// TestServeForwardsAFrameThroughTheBridge is the end-to-end wiring check that
// newServeCmd previously had no coverage for at all: it exercises config
// resolution, keystore selection, auth.Manager credential lookup, and the
// bridge itself, together, through cobra's RunE.
func TestServeForwardsAFrameThroughTheBridge(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	newServeTestEnv(t, f.URL)

	store := keystore.NewEncFile([]byte("test passphrase"))
	if err := store.Set("default", &keystore.Credential{
		AccessToken: "tok-aaaaaaaaaaaa", TokenType: "Bearer",
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	cmd := newServeCmd()
	cmd.SetContext(context.Background())
	var out bytes.Buffer
	cmd.SetIn(strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}` + "\n"))
	cmd.SetOut(&out)

	if err := cmd.RunE(cmd, nil); err != nil {
		t.Fatalf("RunE: %v", err)
	}

	line := strings.TrimSpace(out.String())
	var got struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
		Result  json.RawMessage `json:"result"`
	}
	if err := json.Unmarshal([]byte(line), &got); err != nil {
		t.Fatalf("output is not a JSON-RPC frame: %v\n%s", err, out.String())
	}
	if string(got.ID) != "1" || got.Result == nil {
		t.Fatalf("expected a successful id-1 result, got:\n%s", line)
	}
	if f.MethodCount("initialize") != 1 {
		t.Fatalf("the frame must reach the server as initialize, calls: %v", f.Calls)
	}
}

// mcp-serve must never exit on a 401 from the server: that is the command
// path's behaviour (see status), not the bridge's. The frame's id must still
// be answered so the client does not hang.
func TestServeNeverExitsOnA401(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.SetForceResponse(401, `{"status":401,"error":"Unauthorized"}`)
	newServeTestEnv(t, f.URL)

	store := keystore.NewEncFile([]byte("test passphrase"))
	if err := store.Set("default", &keystore.Credential{
		AccessToken: "revoked-token-aaaa", TokenType: "Bearer",
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	cmd := newServeCmd()
	cmd.SetContext(context.Background())
	var out bytes.Buffer
	cmd.SetIn(strings.NewReader(`{"jsonrpc":"2.0","id":5,"method":"ping"}` + "\n"))
	cmd.SetOut(&out)

	if err := cmd.RunE(cmd, nil); err != nil {
		t.Fatalf("mcp-serve must not return an error for a 401, got: %v", err)
	}

	line := strings.TrimSpace(out.String())
	var got struct {
		ID    json.RawMessage `json:"id"`
		Error *struct {
			Code int `json:"code"`
		} `json:"error"`
	}
	if err := json.Unmarshal([]byte(line), &got); err != nil {
		t.Fatalf("output is not a JSON-RPC frame: %v\n%s", err, out.String())
	}
	if string(got.ID) != "5" || got.Error == nil {
		t.Fatalf("expected a synthesized error for id 5, got:\n%s", line)
	}
}

// mcp-serve never prompts: a missing passphrase for the encfile backend must
// map to exit 3 naming the environment variable, the same way resolveDeps
// does for the other commands.
func TestServeMissingPassphraseIsExitThree(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	newServeTestEnv(t, f.URL)
	t.Setenv("HIVEMEM_PASSPHRASE", "")

	cmd := newServeCmd()
	cmd.SetContext(context.Background())
	cmd.SetIn(strings.NewReader(""))
	cmd.SetOut(&bytes.Buffer{})

	err := cmd.RunE(cmd, nil)
	if err == nil {
		t.Fatal("RunE succeeded, want an error for the missing passphrase")
	}
	if got := exitCodeFor(err); got != 3 {
		t.Fatalf("exitCodeFor(err) = %d, want 3 (err: %v)", got, err)
	}
	if !strings.Contains(err.Error(), "HIVEMEM_PASSPHRASE") {
		t.Fatalf("error must name HIVEMEM_PASSPHRASE, got: %v", err)
	}
}

func TestServeMissingServerIsUsageError(t *testing.T) {
	newServeTestEnv(t, "")
	t.Setenv("HIVEMEM_SERVER", "")

	cmd := newServeCmd()
	cmd.SetContext(context.Background())
	cmd.SetIn(strings.NewReader(""))
	cmd.SetOut(&bytes.Buffer{})

	err := cmd.RunE(cmd, nil)
	if err == nil {
		t.Fatal("RunE succeeded, want an error for the missing server")
	}
	if !strings.Contains(err.Error(), "no server configured") {
		t.Fatalf("error = %v, want a message about the missing server", err)
	}
}
