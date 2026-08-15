package auth

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/httplog"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

func TestDiscoverReportsOAuthDisabled(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	as.DisableOAuth = true

	_, err := Discover(context.Background(), as.URL)
	if !errors.Is(err, ErrOAuthDisabled) {
		t.Fatalf("want ErrOAuthDisabled, got %v", err)
	}
}

func TestDiscoverReportsRegistrationDisabledBeforeAnyPOST(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	as.DisableRegistration = true

	meta, err := Discover(context.Background(), as.URL)
	if err != nil {
		t.Fatalf("Discover: %v", err)
	}
	if meta.RegistrationEndpoint != "" {
		t.Fatal("registration_endpoint must be absent when DCR is disabled")
	}
	if err := meta.RequireRegistration(); !errors.Is(err, ErrRegistrationDisabled) {
		t.Fatalf("want ErrRegistrationDisabled, got %v", err)
	}
}

func TestOAuthLoginBindsBeforeRegisteringAndSucceeds(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "writer"

	m, store := newOAuthManager(t, f.URL, as.URL)

	// Bounded so a reordering regression (register before bind, so the
	// authorize step never matches a registered redirect_uri and the browser
	// never reaches the loopback callback) fails in seconds, not after the
	// production 5-minute wait.
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	var redirectURI string
	role, err := m.LoginWithOAuth(ctx, visitURLCapturingRedirect(&redirectURI))
	if err != nil {
		t.Fatalf("LoginWithOAuth: %v", err)
	}
	if role != "writer" {
		t.Fatalf("role = %q, want writer", role)
	}

	cred, err := store.Get("work")
	if err != nil {
		t.Fatalf("credential was not stored: %v", err)
	}
	if cred.ClientID == "" {
		t.Fatal("client_id was not stored — refresh would be impossible")
	}
	// The token endpoint is discovered exactly once, here. No later process
	// runs discovery, so a login that drops it leaves every refresh posting to
	// the empty string an hour later.
	if cred.TokenEndpoint != as.URL+"/oauth/token" {
		t.Fatalf("stored token_endpoint = %q, want %q — refresh would post nowhere",
			cred.TokenEndpoint, as.URL+"/oauth/token")
	}
	if cred.RefreshToken == "" {
		t.Fatal("refresh token was not stored")
	}
	if cred.ExpiresAt == nil {
		t.Fatal("expires_at was not derived from expires_in")
	}

	// Direct assertion that the registered redirect URI is the one actually
	// bound: register-before-bind would register a stale (or empty-port)
	// URI while the authorize/callback round trip used the real one.
	if got := as.RegisteredRedirects[cred.ClientID]; len(got) != 1 || got[0] != redirectURI {
		t.Fatalf("registered redirect URI = %v, want [%q] (the one actually bound)", got, redirectURI)
	}
}

// A second login must register a fresh client: the stored client_id carries a
// dead loopback port, and redirect URIs are matched byte-exactly.
func TestSecondLoginRegistersAFreshClient(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	f := testsupport.NewFakeMCP()
	defer f.Close()

	m, store := newOAuthManager(t, f.URL, as.URL)

	ctx1, cancel1 := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel1()
	var firstRedirectURI string
	if _, err := m.LoginWithOAuth(ctx1, visitURLCapturingRedirect(&firstRedirectURI)); err != nil {
		t.Fatalf("first login: %v", err)
	}
	first, _ := store.Get("work")

	ctx2, cancel2 := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel2()
	var secondRedirectURI string
	if _, err := m.LoginWithOAuth(ctx2, visitURLCapturingRedirect(&secondRedirectURI)); err != nil {
		t.Fatalf("second login: %v", err)
	}
	second, _ := store.Get("work")

	if first.ClientID == second.ClientID {
		t.Fatal("the second login reused the client_id; its redirect port is dead")
	}
	if len(as.RegisteredRedirects) != 2 {
		t.Fatalf("expected two registrations, got %d", len(as.RegisteredRedirects))
	}

	// Each registration must carry the port that was actually bound for that
	// login, not a stale one left over from the other.
	if got := as.RegisteredRedirects[first.ClientID]; len(got) != 1 || got[0] != firstRedirectURI {
		t.Fatalf("first registered redirect URI = %v, want [%q]", got, firstRedirectURI)
	}
	if got := as.RegisteredRedirects[second.ClientID]; len(got) != 1 || got[0] != secondRedirectURI {
		t.Fatalf("second registered redirect URI = %v, want [%q]", got, secondRedirectURI)
	}
}

// TestOAuthLoginUsesDiscoveryEndpointsNotTheServerURL proves that
// LoginWithOAuth uses the endpoints out of the discovery document rather than
// reconstructing them by appending paths to the URL the CLI was pointed at.
//
// The discovery-only origin below implements ONLY the well-known endpoint; it
// 404s on anything else, including /oauth/register and /oauth/authorize. Its
// discovery document advertises endpoints on a completely different origin
// (the real StubAS). An implementation that rebuilds endpoints from the
// discovery URL instead of using the document's fields would therefore try to
// POST to the discovery-only origin's /oauth/register and fail with 404.
func TestOAuthLoginUsesDiscoveryEndpointsNotTheServerURL(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	f := testsupport.NewFakeMCP()
	defer f.Close()

	discoveryOnly := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/.well-known/oauth-authorization-server" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"issuer":                 as.URL,
			"authorization_endpoint": as.URL + "/oauth/authorize",
			"token_endpoint":         as.URL + "/oauth/token",
			"registration_endpoint":  as.URL + "/oauth/register",
		})
	}))
	defer discoveryOnly.Close()

	m, _ := newOAuthManager(t, f.URL, discoveryOnly.URL)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, err := m.LoginWithOAuth(ctx, visitURL); err != nil {
		t.Fatalf("LoginWithOAuth: %v", err)
	}
}

func newOAuthManager(t *testing.T, mcpURL, asURL string) (*Manager, keystore.Store) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	cache, err := config.LoadCache()
	if err != nil {
		t.Fatalf("LoadCache: %v", err)
	}
	m := NewManager(store, cache, mcpURL, "work")
	m.SetAuthServerURL(asURL)
	return m, store
}

// visitURL stands in for the browser: it follows the authorize redirect, which
// drives the loopback callback.
func visitURL(u string) error {
	if !strings.HasPrefix(u, "http") {
		return errors.New("not a URL: " + u)
	}
	go func() { _, _ = httpGetFollow(u) }()
	return nil
}

// visitURLCapturingRedirect behaves like visitURL but also records the
// redirect_uri the authorize request carried, so a test can assert it against
// what was registered without needing access to the Loopback the production
// code keeps internal.
func visitURLCapturingRedirect(captured *string) func(string) error {
	return func(u string) error {
		parsed, err := url.Parse(u)
		if err != nil {
			return err
		}
		*captured = parsed.Query().Get("redirect_uri")
		return visitURL(u)
	}
}

// Spec: "--verbose must leave neither token in the dumped output". The token
// response body carries a brand-new access and refresh token that the redactor
// has never seen, so a dump written before they are registered prints exactly
// the two values --verbose exists to keep out of a bug report.
//
// The stub's tokens get a unique prefix on purpose: the redactor is global and
// never reset between tests, so a plain "access-1-xxxxxxxxxx" would already be
// scrubbed by another test's registration and this assertion could not fail.
func TestVerboseLoginDumpsTheExchangeWithoutEitherToken(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	as.TokenPrefix = "verboseprobe-"
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "writer"

	m, store := newOAuthManager(t, f.URL, as.URL)

	var dump bytes.Buffer
	httplog.SetOutput(&dump)
	httplog.SetEnabled(true)
	t.Cleanup(func() {
		httplog.SetEnabled(false)
		httplog.SetOutput(os.Stderr)
	})

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	var redirectURI string
	if _, err := m.LoginWithOAuth(ctx, visitURLCapturingRedirect(&redirectURI)); err != nil {
		t.Fatalf("LoginWithOAuth: %v", err)
	}

	got := dump.String()
	if got == "" {
		t.Fatal("--verbose dumped nothing at all")
	}
	if !strings.Contains(got, "/oauth/token") {
		t.Fatalf("the token exchange was not dumped:\n%s", got)
	}

	cred, err := store.Get("work")
	if err != nil {
		t.Fatalf("credential was not stored: %v", err)
	}
	for name, secret := range map[string]string{
		"access token":  cred.AccessToken,
		"refresh token": cred.RefreshToken,
	} {
		if secret == "" {
			t.Fatalf("%s is empty — the assertion below would be vacuous", name)
		}
		if strings.Contains(got, secret) {
			t.Fatalf("the %s reached the --verbose dump:\n%s", name, got)
		}
	}
}
