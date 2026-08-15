package command

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/mcp"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// The delete half of the suppression rule. Only the preserve half
// (TestStatusSuppressionSurvivesACacheWrite) was covered, so a `tools
// --refresh` whose wake_up succeeded still left the record in place: a
// repaired static token — whose fingerprint never changes, so nothing else
// clears it — kept `status` reporting "not probed" and exiting 3 for 24 h.
func TestToolsRefreshClearsTheSuppressionRecordSoStatusRecovers(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{json.RawMessage(`{"name":"search","description":"Search"}`)}

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "test passphrase")
	pinEncFileBackend(t)

	cred := &keystore.Credential{AccessToken: "token-repaired-aaaa", TokenType: "Bearer"}
	store := keystore.NewEncFile([]byte("test passphrase"))
	if err := store.Set("work", cred); err != nil {
		t.Fatalf("seed credential: %v", err)
	}
	cache, err := config.LoadCache()
	if err != nil {
		t.Fatalf("LoadCache: %v", err)
	}
	key := config.CacheKey{ServerURL: f.URL, Profile: "work"}
	// The fingerprint matches the stored credential: this is the case the
	// fingerprint comparison cannot resolve on its own.
	if err := cache.PutAuthFailure(key, &config.AuthFailure{
		CredentialFingerprint: cred.Fingerprint(), At: time.Now().UTC(),
	}); err != nil {
		t.Fatalf("seed suppression: %v", err)
	}

	if _, err := runRoot(t, "--server", f.URL, "--cred-profile", "work",
		"tools", "--refresh"); err != nil {
		t.Fatalf("tools --refresh: %v", err)
	}

	out, err := runRoot(t, "--server", f.URL, "--cred-profile", "work", "status")
	if err != nil {
		t.Fatalf("status after a successful refresh must exit 0, got %v (exit %d)\n%s",
			err, exitCodeFor(err), out)
	}
	if strings.Contains(out, "not probed") {
		t.Fatalf("status still reports the cleared suppression:\n%s", out)
	}
	if !strings.Contains(out, "Status:   ok") {
		t.Fatalf("status did not report ok:\n%s", out)
	}
}

// The same rule on the other probe site: the re-fetch behind the -32003
// cache-invalidation path.
func TestRefetchClearsTheSuppressionRecord(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{json.RawMessage(`{"name":"search","description":"Search"}`)}

	cred := &keystore.Credential{AccessToken: "token-refetch-aaaa", TokenType: "Bearer"}
	d := newTestDeps(t, f.URL, cred)
	d.Client = mcp.New(f.URL, cred.AccessToken, mcp.DefaultTimeouts())

	if err := d.Cache.PutAuthFailure(d.Manager.CacheKey(), &config.AuthFailure{
		CredentialFingerprint: cred.Fingerprint(), At: time.Now().UTC(),
	}); err != nil {
		t.Fatalf("seed suppression: %v", err)
	}

	if _, err := d.refetch(context.Background()); err != nil {
		t.Fatalf("refetch: %v", err)
	}
	e, _ := d.Cache.Get(d.Manager.CacheKey())
	if e != nil && e.LastAuthFailure != nil {
		t.Fatal("a re-fetch whose wake_up returned a result must delete the record")
	}
}

// The negative leg: a probe that FAILS is not evidence of repair. Without this
// the fix could be "always clear", which would defeat the suppression whose
// entire purpose is to stay put across cache writes.
func TestRefetchWithAFailingProbeKeepsTheSuppressionRecord(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = []json.RawMessage{json.RawMessage(`{"name":"search","description":"Search"}`)}
	// tools/list succeeds; the paired wake_up is denied, so no result comes back.
	f.DenyTool("wake_up")

	cred := &keystore.Credential{AccessToken: "token-stillbad-aaaa", TokenType: "Bearer"}
	d := newTestDeps(t, f.URL, cred)
	d.Client = mcp.New(f.URL, cred.AccessToken, mcp.DefaultTimeouts())

	if err := d.Cache.PutAuthFailure(d.Manager.CacheKey(), &config.AuthFailure{
		CredentialFingerprint: cred.Fingerprint(), At: time.Now().UTC(),
	}); err != nil {
		t.Fatalf("seed suppression: %v", err)
	}

	if _, err := d.refetch(context.Background()); err != nil {
		t.Fatalf("refetch: %v", err)
	}
	e, _ := d.Cache.Get(d.Manager.CacheKey())
	if e == nil || e.LastAuthFailure == nil {
		t.Fatal("a failed probe is not evidence of repair; the record must stand")
	}
}
