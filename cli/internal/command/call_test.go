package command

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// callSchema is synthetic, hand-written, and named to look nothing like a real
// capture: fixtures in this repository are never generated from a live system.
const callSchema = `{"name":"add_cell","description":"Store a cell",` +
	`"inputSchema":{"type":"object","required":["content","realm"],` +
	`"properties":{"content":{"type":"string"},"realm":{"type":"string"}}}}`

func setupCallEnv(t *testing.T, f *testsupport.FakeMCP) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	pinEncFileBackend(t)

	store := keystore.NewEncFile([]byte("test passphrase"))
	if err := store.Set("work", &keystore.Credential{
		AccessToken: "token-call-aaaaaaa", TokenType: "Bearer",
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}
	cache, err := config.LoadCache()
	if err != nil {
		t.Fatalf("LoadCache: %v", err)
	}
	if err := cache.PutTools(config.CacheKey{ServerURL: f.URL, Profile: "work"},
		[]json.RawMessage{json.RawMessage(callSchema)}, "writer"); err != nil {
		t.Fatalf("seed cache: %v", err)
	}
}

// The spec exempts --args-json from the unknown-key check but keeps the
// required-property check. Nothing downstream performs it: the server ignores
// keys it does not read rather than rejecting the call, so a missing required
// property comes back as a confident wrong answer.
func TestCallRejectsAMissingRequiredPropertyWithoutCallingTheServer(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	setupCallEnv(t, f)

	out, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"call", "add_cell", "--args-json", `{"content":"x"}`)
	if err == nil {
		t.Fatalf("a missing required property must be rejected, output:\n%s", out)
	}
	if code := exitCodeFor(err); code != 2 {
		t.Fatalf("exit = %d, want 2 (err: %v)", code, err)
	}
	if !strings.Contains(err.Error(), "realm") {
		t.Fatalf("the message must name the missing property, got: %v", err)
	}
	if n := f.ToolCallCount("add_cell"); n != 0 {
		t.Fatalf("the call reached the server %d times, want 0", n)
	}
}

// The exemption itself must survive: --args-json is the escape hatch for a
// payload the cached schema does not describe.
func TestCallStillAcceptsAnUnknownKeyInArgsJSON(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	setupCallEnv(t, f)

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"call", "add_cell", "--args-json",
		`{"content":"x","realm":"work","not_in_the_schema":1}`); err != nil {
		t.Fatalf("--args-json must stay exempt from the unknown-key check: %v", err)
	}
	if n := f.ToolCallCount("add_cell"); n != 1 {
		t.Fatalf("the call reached the server %d times, want 1", n)
	}
}

// A tool the cache has never seen must still be callable — `call` is the way
// to reach one.
func TestCallPassesThroughAToolWithNoCachedSchema(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	setupCallEnv(t, f)

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"call", "not_in_the_cache", "--args-json", `{}`); err != nil {
		t.Fatalf("an uncached tool must still be callable: %v", err)
	}
	if n := f.ToolCallCount("not_in_the_cache"); n != 1 {
		t.Fatalf("the call reached the server %d times, want 1", n)
	}
}
