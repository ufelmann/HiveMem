package command

import (
	"bytes"
	"context"
	"strings"
	"testing"
	"time"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

func TestStatusReportsTheRoleFromWakeUp(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "reader"

	d := newTestDeps(t, f.URL, &keystore.Credential{
		AccessToken: "token-aaaaaaaaaaaa", TokenType: "Bearer",
		Scope: "read write", // a read-write SCOPE with a reader ROLE
	})

	var out bytes.Buffer
	code := runStatus(context.Background(), d, &out, false)
	if code != 0 {
		t.Fatalf("exit = %d, want 0", code)
	}
	if !strings.Contains(out.String(), "reader") {
		t.Fatalf("status must report the effective role, got:\n%s", out.String())
	}
	if strings.Contains(out.String(), "writer") {
		t.Fatal("status must not infer a role from the requested scope")
	}
}

// Each probe carries an Authorization header, and five failures ban the source
// IP for 15 minutes — for every client behind that address.
func TestStatusStopsProbingAfterA401(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.SetForceResponse(401, `{"error":"unauthorized"}`)

	d := newTestDeps(t, f.URL, &keystore.Credential{
		AccessToken: "revoked-token-aaaa", TokenType: "Bearer",
	})

	var out bytes.Buffer
	if code := runStatus(context.Background(), d, &out, false); code != 3 {
		t.Fatalf("first probe exit = %d, want 3", code)
	}
	after := len(f.Calls)

	out.Reset()
	if code := runStatus(context.Background(), d, &out, false); code != 3 {
		t.Fatalf("second run exit = %d, want 3", code)
	}
	if len(f.Calls) != after {
		t.Fatalf("the second run probed again: %d calls, want %d", len(f.Calls), after)
	}
	if !strings.Contains(out.String(), "not probed") {
		t.Fatalf("a suppressed run must say so, got:\n%s", out.String())
	}
}

// A cache write between two status runs must not resurrect the probe.
func TestStatusSuppressionSurvivesACacheWrite(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.SetForceResponse(401, `{"error":"unauthorized"}`)

	d := newTestDeps(t, f.URL, &keystore.Credential{
		AccessToken: "revoked-token-bbbb", TokenType: "Bearer",
	})

	var out bytes.Buffer
	_ = runStatus(context.Background(), d, &out, false)
	after := len(f.Calls)

	// A schema refresh rewrites the same cache entry.
	if err := d.Cache.PutTools(d.Manager.CacheKey(), nil, "reader"); err != nil {
		t.Fatalf("PutTools: %v", err)
	}

	out.Reset()
	_ = runStatus(context.Background(), d, &out, false)
	if len(f.Calls) != after {
		t.Fatal("a cache write wiped the suppression record")
	}
	if !strings.Contains(out.String(), "not probed") {
		t.Fatalf("a suppressed run must say so, got:\n%s", out.String())
	}
}

// A probe that succeeds is evidence of repair and must clear the record.
func TestSuccessfulProbeClearsTheSuppression(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()

	d := newTestDeps(t, f.URL, &keystore.Credential{
		AccessToken: "token-cccccccccccc", TokenType: "Bearer",
	})
	_ = d.Cache.PutAuthFailure(d.Manager.CacheKey(), &config.AuthFailure{
		CredentialFingerprint: (&keystore.Credential{AccessToken: "token-cccccccccccc"}).Fingerprint(),
		At:                    time.Now(),
	})

	var out bytes.Buffer
	if code := runStatus(context.Background(), d, &out, true); code != 0 {
		t.Fatalf("--probe exit = %d, want 0", code)
	}
	e, _ := d.Cache.Get(d.Manager.CacheKey())
	if e != nil && e.LastAuthFailure != nil {
		t.Fatal("a successful probe must delete the suppression record")
	}
}
