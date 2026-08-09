package auth

import (
	"context"
	"errors"
	"testing"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

func newManager(t *testing.T, serverURL string) (*Manager, keystore.Store) {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	cache, err := config.LoadCache()
	if err != nil {
		t.Fatalf("LoadCache: %v", err)
	}
	return NewManager(store, cache, serverURL, "work"), store
}

func TestLoginWithTokenStoresOnlyAfterValidation(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "writer"

	m, store := newManager(t, f.URL)
	role, err := m.LoginWithToken(context.Background(), "pasted-token-aaaaaaaa")
	if err != nil {
		t.Fatalf("LoginWithToken: %v", err)
	}
	if role != "writer" {
		t.Fatalf("role = %q, want writer", role)
	}

	got, err := store.Get("work")
	if err != nil {
		t.Fatalf("credential was not stored: %v", err)
	}
	if got.AccessToken != "pasted-token-aaaaaaaa" {
		t.Fatalf("stored the wrong token: %q", got.AccessToken)
	}
	if !got.IsStatic() {
		t.Fatal("a pasted token must have no refresh token")
	}
}

func TestLoginWithTokenDoesNotStoreARejectedToken(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.ForceStatus = 401
	f.ForceBody = `{"error":"unauthorized"}`

	m, store := newManager(t, f.URL)
	if _, err := m.LoginWithToken(context.Background(), "bad-token-bbbbbbbb"); err == nil {
		t.Fatal("a 401 must fail the login")
	}
	if _, err := store.Get("work"); !errors.Is(err, keystore.ErrNotFound) {
		t.Fatal("a rejected token must not be stored")
	}
}

func TestLoginRecordsTheRoleInTheCache(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Role = "reader"

	m, _ := newManager(t, f.URL)
	if _, err := m.LoginWithToken(context.Background(), "pasted-token-cccccccc"); err != nil {
		t.Fatalf("LoginWithToken: %v", err)
	}

	cache, _ := config.LoadCache()
	e, ok := cache.Get(m.CacheKey())
	if !ok {
		t.Fatal("no cache entry was written")
	}
	if e.Role != "reader" {
		t.Fatalf("recorded role = %q, want reader", e.Role)
	}
}

func TestLogoutDeletesTheCredential(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()

	m, store := newManager(t, f.URL)
	_, _ = m.LoginWithToken(context.Background(), "pasted-token-dddddddd")

	if err := m.Logout(context.Background()); err != nil {
		t.Fatalf("Logout: %v", err)
	}
	if _, err := store.Get("work"); !errors.Is(err, keystore.ErrNotFound) {
		t.Fatal("logout did not delete the credential")
	}
}
