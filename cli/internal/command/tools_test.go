package command

import (
	"bytes"
	"encoding/json"
	"os"
	"strings"
	"testing"

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
