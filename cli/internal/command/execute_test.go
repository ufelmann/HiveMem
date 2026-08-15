package command

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"

	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// runExecute drives the full dispatch path Execute() uses — building the
// command tree, attaching whatever generated subcommands the tool cache
// currently holds, running it, and applying the unknown-command diagnosis —
// without touching os.Args or the process's real stderr.
func runExecute(t *testing.T, args ...string) (stdout string, code int, errLine string) {
	t.Helper()
	var out bytes.Buffer
	code, errLine = execute(args, &out)
	return out.String(), code, errLine
}

// Defect A: runStatus's own report is correct — "not logged in", exit 3 —
// but Execute() printed a trailing "Error: " line with nothing after it,
// because runStatus signals its exit code with an *exitError whose message
// is deliberately empty (fixed.go:136). Cobra's caller must not print a line
// for an error that carries no message.
func TestExecuteFreshStateStatusPrintsNoEmptyErrorLine(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "fresh state passphrase")
	pinEncFileBackend(t)

	out, code, errLine := runExecute(t, "--server", "https://example.invalid", "status")

	if code != 3 {
		t.Fatalf("exit = %d, want 3", code)
	}
	if !strings.Contains(out, "Status:   not logged in") {
		t.Fatalf("report missing the not-logged-in status line, got:\n%s", out)
	}
	if errLine != "" {
		t.Fatalf("errLine = %q, want empty — an exitError with no message must print nothing", errLine)
	}
}

// Unit-level companion of the test above: an *exitError built the same way
// runStatus, newCallCmd and buildCommand build theirs (empty msg, only the
// exit code matters) must format to no line at all.
func TestErrorLineIsEmptyForAnExitErrorWithNoMessage(t *testing.T) {
	if got := errorLine(&exitError{code: 3, msg: ""}); got != "" {
		t.Fatalf("errorLine(empty exitError) = %q, want \"\"", got)
	}
	if got := errorLine(&exitError{code: 3, msg: "credential rejected"}); got != "Error: credential rejected" {
		t.Fatalf("errorLine(non-empty exitError) = %q, want %q", got, "Error: credential rejected")
	}
}

// Defect B, case 1: a real tool name with an empty tool cache (nobody has
// logged in yet) must not be reported as "unknown command" — that points a
// brand-new user at the wrong problem entirely. It must give the exact
// message and exit code `tools` already gives for the same root cause.
func TestExecuteUnknownToolNameWithNoCredentialReportsNotLoggedIn(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "fresh state passphrase")
	pinEncFileBackend(t)

	_, code, errLine := runExecute(t, "--server", "https://example.invalid", "search", "--query", "x")

	if code != 3 {
		t.Fatalf("exit = %d, want 3, errLine=%q", code, errLine)
	}
	if errLine != "Error: not logged in: run `hivemem login`" {
		t.Fatalf("errLine = %q, want the same message `tools` gives", errLine)
	}
}

// Defect B, case 0: no server resolves at all — no --server, no
// HIVEMEM_SERVER, nothing saved in the config. This is the most likely
// first command a brand-new user types, and it must report exactly the
// error resolveDeps itself returns for this state — the same one `status`
// and `tools` already surface — rather than falling through to cobra's
// generic "unknown command".
func TestExecuteUnknownToolNameWithNoServerConfiguredReportsNoServer(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "no server passphrase")
	t.Setenv("HIVEMEM_SERVER", "")
	pinEncFileBackend(t)

	_, code, errLine := runExecute(t, "search", "--query", "x")

	if code != 1 {
		t.Fatalf("exit = %d, want 1 (same as status/tools for this state), errLine=%q", code, errLine)
	}
	if errLine != "Error: "+errNoServerConfigured.Error() {
		t.Fatalf("errLine = %q, want the exact resolveDeps message: %q",
			errLine, "Error: "+errNoServerConfigured.Error())
	}
}

// Defect B, case 2: a credential exists but the tool cache was never
// populated (login happened, `tools`/`tools --refresh` never ran, or the
// cache file was wiped). The unrecognised name is very likely a tool that
// just is not cached yet, so the message must name the command that fixes
// it: `hivemem tools --refresh`.
func TestExecuteUnknownToolNameWithNoCacheNamesTheRefreshCommand(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "cache test passphrase")
	pinEncFileBackend(t)

	store := keystore.NewEncFile([]byte("cache test passphrase"))
	if err := store.Set("default", &keystore.Credential{
		AccessToken: "token-nocache-aaaa", TokenType: "Bearer",
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	_, code, errLine := runExecute(t, "--server", "https://example.invalid", "search", "--query", "x")

	if code != 2 {
		t.Fatalf("exit = %d, want 2, errLine=%q", code, errLine)
	}
	if !strings.Contains(errLine, "hivemem tools --refresh") {
		t.Fatalf("errLine = %q, must name the refresh command", errLine)
	}
}

// Defect B, case 3: credential and cache are both present and the typed name
// really is not a tool. This must keep cobra's own "unknown command" error
// and its exit code untouched — the diagnosis must never swallow a genuine
// typo.
func TestExecuteGenuineTypoKeepsCobraUnknownCommandError(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{
		json.RawMessage(`{"name":"search","description":"Search the knowledge base"}`),
	}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "typo test passphrase")
	pinEncFileBackend(t)
	withStdin(t, "static-token-typo-aaaa\n")

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"login", "--token"); err != nil {
		t.Fatalf("login --token: %v", err)
	}
	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work", "tools"); err != nil {
		t.Fatalf("tools: %v", err)
	}

	_, code, errLine := runExecute(t, "--server", f.URL, "--cred-profile", "work",
		"totally-bogus-name")

	if code != 1 {
		t.Fatalf("exit = %d, want 1 (cobra's default), errLine=%q", code, errLine)
	}
	if !strings.Contains(errLine, `unknown command "totally-bogus-name"`) {
		t.Fatalf("errLine = %q, want cobra's own unknown-command message", errLine)
	}
}
