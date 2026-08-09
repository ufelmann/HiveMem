package command

import (
	"bytes"
	"errors"
	"strings"
	"testing"

	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// e2ePassphrase is fixed and local to this file: these tests force the
// encrypted-file keystore backend (see setupE2ECredential) so the passphrase
// never has to be typed or discovered, only matched between seeding and read.
const e2ePassphrase = "e2e test passphrase, not a real secret"

// setupE2ECredential seeds a static (non-expiring, no-refresh-token) credential
// for the "work" profile into the encrypted-file backend, and points the
// process at a fresh config/data directory so tests never share state. The
// session bus address is cleared to force the encfile backend deterministically
// — the same technique TestResolveDepsMapsMissingPassphraseToExitThree uses —
// so this does not depend on whether a real keyring happens to be reachable
// from the machine running the suite.
func setupE2ECredential(t *testing.T) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", e2ePassphrase)

	store := keystore.NewEncFile([]byte(e2ePassphrase))
	cred := &keystore.Credential{AccessToken: "token-e2e-aaaaaaaaaaaa", TokenType: "Bearer"}
	if err := store.Set("work", cred); err != nil {
		t.Fatalf("seed credential: %v", err)
	}
}

// runGenerated builds the real command tree (fixed commands plus the fixture's
// generated ones), points it at the fake server, and runs it exactly the way
// main() would via Execute() — except the exit code is returned instead of
// passed to os.Exit, and root.Execute()'s own error line is not printed to the
// real process stderr.
func runGenerated(t *testing.T, f *testsupport.FakeMCP, args ...string) (stdout string, exitCode int) {
	t.Helper()
	setupE2ECredential(t)

	root := newRootCmd()
	attachGenerated(root, loadFixture(t))

	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	full := append([]string{"--server", f.URL, "--cred-profile", "work"}, args...)
	root.SetArgs(full)

	err := root.Execute()
	if err != nil {
		return out.String(), exitCodeFor(err)
	}
	return out.String(), 0
}

// This is the RunE closure's actual product: BuildArgs -> ValidateArgs ->
// CallTool -> Render -> ExitCodeForResult. None of the other generator tests
// exercise it; they all stop at spec generation or command registration.
func TestGeneratedSearchCallsToolWithBuiltArgsAndRendersOutput(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()

	var gotArgs map[string]any
	f.ToolHandler = func(name string, args map[string]any) (any, error) {
		gotArgs = args
		return map[string]any{"echo-marker": "needle-value"}, nil
	}

	out, code := runGenerated(t, f, "search", "--query", "hello", "--limit", "5")

	if code != 0 {
		t.Fatalf("exit = %d, want 0, output:\n%s", code, out)
	}
	if f.ToolCallCount("search") != 1 {
		t.Fatalf("search called %d times, want 1", f.ToolCallCount("search"))
	}
	if gotArgs["query"] != "hello" {
		t.Fatalf("query = %v, want %q", gotArgs["query"], "hello")
	}
	// JSON round-trips every number through the fake HTTP server as float64.
	if gotArgs["limit"] != float64(5) {
		t.Fatalf("limit = %v (%T), want 5", gotArgs["limit"], gotArgs["limit"])
	}
	if !strings.Contains(out, "needle-value") {
		t.Fatalf("rendered output does not contain the tool's result:\n%s", out)
	}
}

// A failed tool call is an HTTP 200 with isError:true (mirrored by FakeMCP's
// ToolHandler-returns-error path), so without ExitCodeForResult a wrapping
// script would see exit 0 and treat a failed write as a success.
func TestGeneratedSubcommandExitsFiveOnAnIsErrorResult(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.ToolHandler = func(name string, args map[string]any) (any, error) {
		return nil, errors.New("boom: cell rejected")
	}

	out, code := runGenerated(t, f, "search", "--query", "hello")

	if code != 5 {
		t.Fatalf("exit = %d, want 5, output:\n%s", code, out)
	}
	if f.ToolCallCount("search") != 1 {
		t.Fatalf("search called %d times, want 1", f.ToolCallCount("search"))
	}
}

// The server does not validate arguments against the schema, so an
// out-of-enum --include value must never leave the client: this is the exact
// scenario the client-side validation in this task exists to catch.
func TestGeneratedSearchRejectsAnOutOfEnumIncludeWithoutCallingTheServer(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	called := false
	f.ToolHandler = func(name string, args map[string]any) (any, error) {
		called = true
		return map[string]any{}, nil
	}

	out, code := runGenerated(t, f, "search", "--query", "hello", "--include", "bogus")

	if code != 2 {
		t.Fatalf("exit = %d, want 2, output:\n%s", code, out)
	}
	if called || f.ToolCallCount("search") != 0 {
		t.Fatalf("search called %d times, want 0", f.ToolCallCount("search"))
	}
}

// The companion of the rejection test above: a value that IS in the item enum
// must still reach the server, so the validation is not accidentally blanket.
func TestGeneratedSearchAcceptsAnInEnumIncludeValue(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.ToolHandler = func(name string, args map[string]any) (any, error) {
		return map[string]any{}, nil
	}

	out, code := runGenerated(t, f, "search", "--query", "hello", "--include", "scores")
	if code != 0 {
		t.Fatalf("valid --include value rejected: exit = %d, output:\n%s", code, out)
	}
	if f.ToolCallCount("search") != 1 {
		t.Fatalf("search called %d times, want 1", f.ToolCallCount("search"))
	}
}
