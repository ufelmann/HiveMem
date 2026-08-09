package command

import (
	"bytes"
	"encoding/json"
	"os"
	"strings"
	"testing"

	"github.com/visterion/hivemem/cli/internal/httplog"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// withStdin replaces os.Stdin for the length of a test. readTokenFromStdin
// reads the real os.Stdin deliberately — the token must never be an argv
// element — so this is the only way to drive `login --token` end to end.
func withStdin(t *testing.T, content string) {
	t.Helper()
	r, w, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	if _, err := w.WriteString(content); err != nil {
		t.Fatalf("write stdin: %v", err)
	}
	_ = w.Close()

	saved := os.Stdin
	os.Stdin = r
	t.Cleanup(func() {
		os.Stdin = saved
		_ = r.Close()
	})
}

// runRoot builds a fresh command tree — as a new process would — and runs it.
func runRoot(t *testing.T, args ...string) (string, error) {
	t.Helper()
	savedServer, savedProfile := opts.server, opts.credProfile
	t.Cleanup(func() { opts.server, opts.credProfile = savedServer, savedProfile })

	root := newRootCmd()
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	root.SetArgs(args)
	err := root.Execute()
	return out.String(), err
}

// The recommended headless first-contact sequence, in order: `login --token`
// and then `tools`. login recorded a NIL tool set with a fresh fetched_at, so
// `tools` saw a non-stale cache entry, skipped the fetch, iterated nil and
// printed nothing while exiting 0 — for 24 h, and a token re-login wiped a
// previously cached tool set the same way.
func TestToolsImmediatelyAfterTokenLoginListsTheTools(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{
		json.RawMessage(`{"name":"search","description":"Search the knowledge base"}`),
		json.RawMessage(`{"name":"add_cell","description":"Store a cell"}`),
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	pinEncFileBackend(t)
	withStdin(t, "static-token-aaaaaaaaaa\n")

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"login", "--token"); err != nil {
		t.Fatalf("login --token: %v", err)
	}

	out, err := runRoot(t, "--server", f.URL, "--cred-profile", "work", "tools")
	if err != nil {
		t.Fatalf("tools: %v", err)
	}
	for _, want := range []string{"search", "add_cell"} {
		if !strings.Contains(out, want) {
			t.Fatalf("tools printed no %q — output was:\n%q", want, out)
		}
	}
	// One fetch, performed by the login. A second one here would mean the cache
	// entry login wrote was unusable, which is the same defect one step later.
	if n := f.MethodCount("tools/list"); n != 1 {
		t.Fatalf("tools/list was called %d times, want exactly 1 (from the login)", n)
	}
}

// The flag was declared and read by nothing at all: --help promised redacted
// HTTP logging and the run produced none. This covers the wiring in both
// directions — a dump when asked for, silence when not.
func TestVerboseFlagControlsTheHTTPDump(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{json.RawMessage(`{"name":"search","description":"Search"}`)}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	pinEncFileBackend(t)

	store := keystore.NewEncFile([]byte("test passphrase"))
	if err := store.Set("work", &keystore.Credential{
		AccessToken: "token-verbose-aaaa", TokenType: "Bearer",
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	var dump bytes.Buffer
	httplog.SetOutput(&dump)
	t.Cleanup(func() {
		httplog.SetEnabled(false)
		httplog.SetOutput(os.Stderr)
	})

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"--verbose", "tools", "--refresh"); err != nil {
		t.Fatalf("tools --refresh --verbose: %v", err)
	}
	if !strings.Contains(dump.String(), "tools/list") {
		t.Fatalf("--verbose dumped no HTTP exchange, got:\n%q", dump.String())
	}
	if strings.Contains(dump.String(), "token-verbose-aaaa") {
		t.Fatalf("the bearer token reached the dump:\n%s", dump.String())
	}

	dump.Reset()
	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"tools", "--refresh"); err != nil {
		t.Fatalf("tools --refresh: %v", err)
	}
	if dump.Len() != 0 {
		t.Fatalf("a run without --verbose dumped:\n%s", dump.String())
	}
}

// A tool named "status" collides with the fixed `status` command, so
// attachGenerated skips it and it never becomes a subcommand. Without a
// visible marker, `hivemem tools` still lists it, `hivemem --help` does not
// show it as a subcommand, and nothing explains where it went — the "silent
// success" failure mode. `tools` must mark it and name the escape hatch.
func TestToolsMarksAToolShadowedByAFixedCommand(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{
		json.RawMessage(`{"name":"search","description":"Search the knowledge base"}`),
		json.RawMessage(`{"name":"status","description":"Report ingestion status"}`),
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	pinEncFileBackend(t)
	withStdin(t, "static-token-bbbbbbbbbb\n")

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"login", "--token"); err != nil {
		t.Fatalf("login --token: %v", err)
	}

	out, err := runRoot(t, "--server", f.URL, "--cred-profile", "work", "tools")
	if err != nil {
		t.Fatalf("tools: %v", err)
	}

	var statusLine, searchLine string
	for _, line := range strings.Split(out, "\n") {
		switch {
		case strings.HasPrefix(line, "status"):
			statusLine = line
		case strings.HasPrefix(line, "search"):
			searchLine = line
		}
	}
	if statusLine == "" {
		t.Fatalf("tools output has no line for the shadowed %q tool:\n%s", "status", out)
	}
	if !strings.Contains(statusLine, "shadowed") ||
		!strings.Contains(statusLine, "hivemem call status") {
		t.Fatalf("shadowed %q tool line carries no visible marker naming the "+
			"escape hatch, got:\n%q", "status", statusLine)
	}
	if searchLine == "" {
		t.Fatalf("tools output has no line for %q:\n%s", "search", out)
	}
	if strings.Contains(searchLine, "shadowed") {
		t.Fatalf("non-colliding tool %q wrongly marked shadowed:\n%q", "search", searchLine)
	}
}

// The --json form of `tools` must carry the same information as a field, not
// only as text in the human-readable form.
func TestToolsJSONMarksAToolShadowedByAFixedCommand(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{
		json.RawMessage(`{"name":"search","description":"Search the knowledge base"}`),
		json.RawMessage(`{"name":"status","description":"Report ingestion status"}`),
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	pinEncFileBackend(t)
	withStdin(t, "static-token-cccccccccc\n")

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"login", "--token"); err != nil {
		t.Fatalf("login --token: %v", err)
	}

	out, err := runRoot(t, "--server", f.URL, "--cred-profile", "work", "tools", "--json")
	if err != nil {
		t.Fatalf("tools --json: %v", err)
	}

	var entries []struct {
		Name       string `json:"name"`
		Shadowed   bool   `json:"shadowed"`
		ShadowedBy string `json:"shadowed_by"`
	}
	if err := json.Unmarshal([]byte(out), &entries); err != nil {
		t.Fatalf("tools --json did not produce a JSON array: %v\noutput:\n%s", err, out)
	}

	found := false
	for _, e := range entries {
		if e.Name != "status" {
			if e.Shadowed {
				t.Fatalf("non-colliding tool %q wrongly marked shadowed in JSON: %+v", e.Name, e)
			}
			continue
		}
		found = true
		if !e.Shadowed || e.ShadowedBy == "" {
			t.Fatalf("shadowed tool %q missing the shadowed marker in JSON: %+v", "status", e)
		}
	}
	if !found {
		t.Fatalf("tools --json output has no entry for %q:\n%s", "status", out)
	}
}
