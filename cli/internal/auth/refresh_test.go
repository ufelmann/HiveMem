package auth

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

func managerWithCredential(t *testing.T, as *testsupport.StubAS, expiresIn time.Duration) (*Manager, keystore.Store) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	cache, _ := config.LoadCache()
	m := NewManager(store, cache, "http://mcp.invalid", "work")
	m.SetAuthServerURL(as.URL)
	m.SetTokenEndpoint(as.URL + "/oauth/token")

	exp := time.Now().Add(expiresIn).UTC()
	if err := store.Set("work", &keystore.Credential{
		AccessToken:  "access-0-xxxxxxxxxx",
		RefreshToken: "refresh-0-xxxxxxxxxx",
		TokenType:    "Bearer",
		ClientID:     "client-1",
		ExpiresAt:    &exp,
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}
	return m, store
}

// The threshold boundary. A guard comparing against `now` instead of the
// threshold never fires — and "zero refreshes" is indistinguishable from
// "one refresh plus one skip" without both legs of this test.
func TestRefreshFiresOnlyInsideTheWindow(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	m, _ := managerWithCredential(t, as, RefreshSkew+time.Second) // expires_at - 61s
	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("Credential: %v", err)
	}
	if as.RefreshCalls != 0 {
		t.Fatalf("refreshed %d times outside the window, want 0", as.RefreshCalls)
	}

	m2, _ := managerWithCredential(t, as, RefreshSkew-time.Second) // expires_at - 59s
	if _, err := m2.Credential(context.Background()); err != nil {
		t.Fatalf("Credential: %v", err)
	}
	if as.RefreshCalls != 1 {
		t.Fatalf("refreshed %d times inside the window, want exactly 1", as.RefreshCalls)
	}
}

func TestConcurrentRefreshesIssueExactlyOneTokenRequest(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	m, _ := managerWithCredential(t, as, RefreshSkew-time.Second)

	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := m.Credential(context.Background()); err != nil {
				t.Errorf("Credential: %v", err)
			}
		}()
	}
	wg.Wait()

	if as.RefreshCalls != 1 {
		t.Fatalf("%d refresh calls, want exactly 1 — the second would be treated "+
			"as replay and revoke the whole chain", as.RefreshCalls)
	}
}

// client_id must be present on EVERY refresh. A blob rebuilt purely from the
// token response drops it, and only the second refresh fails.
func TestTwoSuccessiveRefreshesBothCarryClientID(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	m, store := managerWithCredential(t, as, RefreshSkew-time.Second)

	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("first refresh: %v", err)
	}
	// Age the freshly written credential back into the window.
	c, _ := store.Get("work")
	exp := time.Now().Add(RefreshSkew - time.Second).UTC()
	c.ExpiresAt = &exp
	_ = store.Set("work", c)

	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("second refresh: %v", err)
	}

	if as.RefreshCalls != 2 {
		t.Fatalf("refresh calls = %d, want 2", as.RefreshCalls)
	}
	for i, id := range as.RefreshClientIDs {
		if id == "" {
			t.Fatalf("refresh %d carried no client_id", i+1)
		}
	}
}

// A successful refresh must clear the marker. If it does not, refresh #2 sees a
// stale marker and demands a re-login — and every concurrency test above still
// passes, because none of them drives two successive successful refreshes.
func TestSuccessfulRefreshClearsTheInFlightMarker(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	m, store := managerWithCredential(t, as, RefreshSkew-time.Second)
	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("refresh: %v", err)
	}
	c, _ := store.Get("work")
	if c.RefreshInFlight != nil {
		t.Fatal("the in-flight marker survived a successful refresh")
	}
}

// A lost response leaves the server rotated. Re-presenting the same token looks
// like replay and revokes the entire grant, so the CLI must not try.
func TestLostRefreshResponseDoesNotRetryTheSameToken(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	as.DropRefreshResponse = true

	m, store := managerWithCredential(t, as, RefreshSkew-time.Second)

	if _, err := m.Credential(context.Background()); err == nil {
		t.Fatal("a dropped refresh response must surface as an error")
	}
	c, _ := store.Get("work")
	if c.RefreshInFlight == nil {
		t.Fatal("the marker must persist so the next attempt knows the outcome is unknown")
	}

	before := as.RefreshCalls
	_, err := m.Credential(context.Background())
	if !errors.Is(err, ErrReloginRequired) {
		t.Fatalf("want ErrReloginRequired, got %v", err)
	}
	if as.RefreshCalls != before {
		t.Fatalf("the CLI re-presented the token: %d calls, want %d", as.RefreshCalls, before)
	}
}

// After the marker ages out, one retry is permitted — otherwise a SIGKILL
// between writing the marker and sending the request latches the profile.
func TestAgedMarkerPermitsOneRetry(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	m, store := managerWithCredential(t, as, RefreshSkew-time.Second)
	c, _ := store.Get("work")
	old := time.Now().Add(-InFlightMaxAge - time.Minute).UTC()
	c.RefreshInFlight = &keystore.InFlight{At: old}
	_ = store.Set("work", c)

	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("an aged marker must permit one retry: %v", err)
	}
	if as.RefreshCalls != 1 {
		t.Fatalf("refresh calls = %d, want 1", as.RefreshCalls)
	}
}

// The production wiring, reproduced literally. Every other test in this file
// calls SetTokenEndpoint, which is what hid the fact that NOTHING in production
// ever does: resolveDeps builds the manager with NewManager and nothing else, so
// the endpoint has to come out of the stored credential or the refresh posts to
// the empty string ~59 minutes after an OAuth login.
func TestRefreshUsesTheTokenEndpointFromTheStoredCredential(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	cache, _ := config.LoadCache()
	// Exactly resolveDeps: no SetAuthServerURL, no SetTokenEndpoint.
	m := NewManager(store, cache, "http://mcp.invalid", "work")

	exp := time.Now().Add(RefreshSkew - time.Second).UTC()
	if err := store.Set("work", &keystore.Credential{
		AccessToken:   "access-0-xxxxxxxxxx",
		RefreshToken:  "refresh-0-xxxxxxxxxx",
		TokenType:     "Bearer",
		ClientID:      "client-1",
		TokenEndpoint: as.URL + "/oauth/token",
		ExpiresAt:     &exp,
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	cred, err := m.Credential(context.Background())
	if err != nil {
		t.Fatalf("refresh through the production wiring failed: %v", err)
	}
	if as.RefreshCalls != 1 {
		t.Fatalf("refresh calls = %d, want 1", as.RefreshCalls)
	}
	if cred.AccessToken == "access-0-xxxxxxxxxx" {
		t.Fatal("the credential was not refreshed")
	}
	// The endpoint must survive the rotation, or refresh #2 has nowhere to go.
	stored, err := store.Get("work")
	if err != nil {
		t.Fatalf("re-read credential: %v", err)
	}
	if stored.TokenEndpoint != as.URL+"/oauth/token" {
		t.Fatalf("stored token_endpoint = %q, want %q",
			stored.TokenEndpoint, as.URL+"/oauth/token")
	}
}

// A credential stored before the endpoint was persisted must not be a dead end.
func TestRefreshRediscoversTheEndpointForAnOlderCredential(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	cache, _ := config.LoadCache()
	// The stub AS also serves discovery, so pointing the manager at it is the
	// single-host deployment: server URL == auth server URL.
	m := NewManager(store, cache, as.URL, "work")

	exp := time.Now().Add(RefreshSkew - time.Second).UTC()
	if err := store.Set("work", &keystore.Credential{
		AccessToken:  "access-0-xxxxxxxxxx",
		RefreshToken: "refresh-0-xxxxxxxxxx",
		TokenType:    "Bearer",
		ClientID:     "client-1",
		ExpiresAt:    &exp,
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("an endpoint-less credential must fall back to discovery: %v", err)
	}
	if as.RefreshCalls != 1 {
		t.Fatalf("refresh calls = %d, want 1", as.RefreshCalls)
	}
	stored, _ := store.Get("work")
	if stored.TokenEndpoint == "" {
		t.Fatal("the rediscovered endpoint must be written back")
	}
}

// A configuration-level failure sends no request, so it must not latch the
// profile: the marker means "the server may have rotated and we do not know".
func TestUnresolvableEndpointDoesNotLatchTheProfile(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	as.DisableOAuth = true // discovery answers 404

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	cache, _ := config.LoadCache()
	m := NewManager(store, cache, as.URL, "work")

	exp := time.Now().Add(RefreshSkew - time.Second).UTC()
	if err := store.Set("work", &keystore.Credential{
		AccessToken:  "access-0-xxxxxxxxxx",
		RefreshToken: "refresh-0-xxxxxxxxxx",
		TokenType:    "Bearer",
		ClientID:     "client-1",
		ExpiresAt:    &exp,
	}); err != nil {
		t.Fatalf("seed credential: %v", err)
	}

	if _, err := m.Credential(context.Background()); err == nil {
		t.Fatal("an unresolvable token endpoint must surface as an error")
	}
	c, err := store.Get("work")
	if err != nil {
		t.Fatalf("re-read credential: %v", err)
	}
	if c.RefreshInFlight != nil {
		t.Fatal("no request was sent, so the in-flight marker must not be latched")
	}
}

func TestStaticTokenNeverRefreshes(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()

	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	store := keystore.NewEncFile([]byte("pw"))
	cache, _ := config.LoadCache()
	m := NewManager(store, cache, "http://mcp.invalid", "work")
	m.SetTokenEndpoint(as.URL + "/oauth/token")

	_ = store.Set("work", &keystore.Credential{
		AccessToken: "static-token-aaaaaaaa", TokenType: "Bearer",
	})

	if _, err := m.Credential(context.Background()); err != nil {
		t.Fatalf("Credential: %v", err)
	}
	if as.RefreshCalls != 0 {
		t.Fatal("a static token has no refresh path and must never call /oauth/token")
	}
}
