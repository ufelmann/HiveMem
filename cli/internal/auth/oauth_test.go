package auth

import (
	"context"
	"errors"
	"strings"
	"testing"

	"github.com/visterion/hivemem/cli/internal/config"
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

	role, err := m.LoginWithOAuth(context.Background(), visitURL)
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
	if cred.RefreshToken == "" {
		t.Fatal("refresh token was not stored")
	}
	if cred.ExpiresAt == nil {
		t.Fatal("expires_at was not derived from expires_in")
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

	if _, err := m.LoginWithOAuth(context.Background(), visitURL); err != nil {
		t.Fatalf("first login: %v", err)
	}
	first, _ := store.Get("work")

	if _, err := m.LoginWithOAuth(context.Background(), visitURL); err != nil {
		t.Fatalf("second login: %v", err)
	}
	second, _ := store.Get("work")

	if first.ClientID == second.ClientID {
		t.Fatal("the second login reused the client_id; its redirect port is dead")
	}
	if len(as.RegisteredRedirects) != 2 {
		t.Fatalf("expected two registrations, got %d", len(as.RegisteredRedirects))
	}
}

func TestOAuthLoginUsesDiscoveryEndpointsNotTheServerURL(t *testing.T) {
	as := testsupport.NewStubAS()
	defer as.Close()
	f := testsupport.NewFakeMCP()
	defer f.Close()

	// Split-host: discovery publishes endpoints on the issuer, which is not
	// the URL the CLI was pointed at.
	as.IssuerOverride = as.URL

	m, _ := newOAuthManager(t, f.URL, as.URL)
	if _, err := m.LoginWithOAuth(context.Background(), visitURL); err != nil {
		t.Fatalf("LoginWithOAuth: %v", err)
	}
	if as.RefreshCalls != 0 {
		t.Fatal("login must not call the refresh grant")
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
