package keystore

import (
	"errors"
	"strings"
	"testing"
)

// fakeBackend lets the selection logic be exercised on any OS.
type fakeBackend struct {
	name      string
	available bool
	held      map[string]*Credential
}

func (f *fakeBackend) Name() string { return f.name }
func (f *fakeBackend) Get(profile string) (*Credential, error) {
	if c, ok := f.held[profile]; ok {
		return c, nil
	}
	return nil, ErrNotFound
}
func (f *fakeBackend) Set(profile string, c *Credential) error {
	if f.held == nil {
		f.held = map[string]*Credential{}
	}
	f.held[profile] = c
	return nil
}
func (f *fakeBackend) Delete(profile string) error { delete(f.held, profile); return nil }

func TestSelectPrefersTheKeyringWhenAvailable(t *testing.T) {
	keyring := &fakeBackend{name: "keyring", available: true}
	file := &fakeBackend{name: "encrypted file", available: true}

	got, err := selectFrom(candidate{keyring, true}, candidate{file, true}, SelectOptions{})
	if err != nil {
		t.Fatalf("selectFrom: %v", err)
	}
	if got.Name() != "keyring" {
		t.Fatalf("selected %q, want keyring", got.Name())
	}
}

func TestSelectFallsBackToTheFileWhenNoKeyring(t *testing.T) {
	keyring := &fakeBackend{name: "keyring"}
	file := &fakeBackend{name: "encrypted file"}

	got, err := selectFrom(candidate{keyring, false}, candidate{file, true},
		SelectOptions{Passphrase: []byte("pw")})
	if err != nil {
		t.Fatalf("selectFrom: %v", err)
	}
	if got.Name() != "encrypted file" {
		t.Fatalf("selected %q, want the encrypted file fallback", got.Name())
	}
}

func TestSelectRequiresAPassphraseForTheFileBackend(t *testing.T) {
	keyring := &fakeBackend{name: "keyring"}
	file := &fakeBackend{name: "encrypted file"}

	// No passphrase and no prompt: this is the mcp-serve case, and it must
	// fail fast rather than block on a terminal that is the JSON-RPC pipe.
	_, err := selectFrom(candidate{keyring, false}, candidate{file, true}, SelectOptions{})
	if !errors.Is(err, ErrPassphraseRequired) {
		t.Fatalf("want ErrPassphraseRequired, got %v", err)
	}
}

func TestSelectUsesThePromptWhenOneIsProvided(t *testing.T) {
	keyring := &fakeBackend{name: "keyring"}
	file := &fakeBackend{name: "encrypted file"}
	called := false

	_, err := selectFrom(candidate{keyring, false}, candidate{file, true}, SelectOptions{
		PassphrasePrompt: func() ([]byte, error) { called = true; return []byte("pw"), nil },
	})
	if err != nil {
		t.Fatalf("selectFrom: %v", err)
	}
	if !called {
		t.Fatal("the prompt was not used")
	}
}

func TestSelectHonoursForceBackend(t *testing.T) {
	keyring := &fakeBackend{name: "keyring", available: true}
	file := &fakeBackend{name: "encrypted file"}

	got, err := selectFrom(candidate{keyring, true}, candidate{file, true},
		SelectOptions{ForceBackend: "encfile", Passphrase: []byte("pw")})
	if err != nil {
		t.Fatalf("selectFrom: %v", err)
	}
	if got.Name() != "encrypted file" {
		t.Fatalf("ForceBackend was ignored, selected %q", got.Name())
	}
}

func TestSelectRejectsAnUnrecognizedForceBackend(t *testing.T) {
	keyring := &fakeBackend{name: "keyring", available: true}
	file := &fakeBackend{name: "encrypted file"}

	_, err := selectFrom(candidate{keyring, true}, candidate{file, true},
		SelectOptions{ForceBackend: "totally-bogus"})
	if err == nil {
		t.Fatal("want an error for an unrecognized ForceBackend, got nil")
	}
	msg := err.Error()
	if !strings.Contains(msg, "totally-bogus") {
		t.Fatalf("error %q does not name the bad value", msg)
	}
	if !strings.Contains(msg, BackendKeyring) || !strings.Contains(msg, BackendEncFile) {
		t.Fatalf("error %q does not list the accepted backend names", msg)
	}
}

func TestOtherBackendHoldsReportsAMismatch(t *testing.T) {
	keyring := &fakeBackend{name: "keyring", available: true}
	file := &fakeBackend{name: "encrypted file", available: true}
	_ = file.Set("work", &Credential{AccessToken: "aaaaaaaaaaaa"})

	// Selected backend is the keyring, but the credential is in the file.
	other, ok := otherBackendHolds("work", keyring, []Store{keyring, file})
	if !ok {
		t.Fatal("expected a mismatch hint")
	}
	if other != "encrypted file" {
		t.Fatalf("hint names %q, want the encrypted file", other)
	}
}

func TestOtherBackendHoldsIsQuietWhenNothingElseHasIt(t *testing.T) {
	keyring := &fakeBackend{name: "keyring", available: true}
	file := &fakeBackend{name: "encrypted file", available: true}
	if _, ok := otherBackendHolds("work", keyring, []Store{keyring, file}); ok {
		t.Fatal("no other backend holds it; there should be no hint")
	}
}
