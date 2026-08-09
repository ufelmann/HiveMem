package keystore

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"sync"
	"testing"
	"time"
)

func testStore(t *testing.T) Store {
	t.Helper()
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	return NewEncFile([]byte("correct horse battery staple"))
}

func sampleCredential() *Credential {
	exp := time.Now().Add(time.Hour).UTC().Truncate(time.Second)
	return &Credential{
		AccessToken:  "access-token-aaaaaaaaaaaa",
		RefreshToken: "refresh-token-bbbbbbbbbbbb",
		TokenType:    "Bearer",
		ClientID:     "client-abc",
		Scope:        "read write",
		ExpiresAt:    &exp,
	}
}

func TestEncFileRoundTrip(t *testing.T) {
	s := testStore(t)
	want := sampleCredential()

	if err := s.Set("work", want); err != nil {
		t.Fatalf("Set: %v", err)
	}
	got, err := s.Get("work")
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if got.AccessToken != want.AccessToken || got.RefreshToken != want.RefreshToken {
		t.Fatalf("tokens did not round trip: %+v", got)
	}
	if got.ClientID != want.ClientID {
		t.Fatalf("client_id did not round trip — refresh would be impossible: %+v", got)
	}
	if got.ExpiresAt == nil || !got.ExpiresAt.Equal(*want.ExpiresAt) {
		t.Fatalf("expires_at did not round trip: %+v", got.ExpiresAt)
	}
}

func TestEncFileWrongPassphraseIsRejected(t *testing.T) {
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	good := NewEncFile([]byte("correct horse battery staple"))
	if err := good.Set("work", sampleCredential()); err != nil {
		t.Fatalf("Set: %v", err)
	}

	bad := NewEncFile([]byte("wrong passphrase entirely"))
	if _, err := bad.Get("work"); err == nil {
		t.Fatal("a wrong passphrase must not decrypt the blob")
	}
}

func TestEncFileUsesOneFilePerProfile(t *testing.T) {
	base := t.TempDir()
	t.Setenv("XDG_DATA_HOME", base)
	s := NewEncFile([]byte("correct horse battery staple"))

	if err := s.Set("work", sampleCredential()); err != nil {
		t.Fatalf("Set work: %v", err)
	}
	if err := s.Set("personal", sampleCredential()); err != nil {
		t.Fatalf("Set personal: %v", err)
	}

	dir := filepath.Join(base, "hivemem")
	for _, name := range []string{"creds-work.enc", "creds-personal.enc"} {
		if _, err := os.Stat(filepath.Join(dir, name)); err != nil {
			t.Fatalf("expected a per-profile file %s: %v", name, err)
		}
	}
}

func TestEncFileFileModeIs0600(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX modes are not meaningful on Windows")
	}
	base := t.TempDir()
	t.Setenv("XDG_DATA_HOME", base)
	s := NewEncFile([]byte("correct horse battery staple"))
	if err := s.Set("work", sampleCredential()); err != nil {
		t.Fatalf("Set: %v", err)
	}
	info, err := os.Stat(filepath.Join(base, "hivemem", "creds-work.enc"))
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Fatalf("mode = %o, want 600", perm)
	}
}

func TestEncFileGetMissingReturnsErrNotFound(t *testing.T) {
	s := testStore(t)
	_, err := s.Get("nope")
	if !errors.Is(err, ErrNotFound) {
		t.Fatalf("want ErrNotFound, got %v", err)
	}
}

func TestEncFileDelete(t *testing.T) {
	s := testStore(t)
	_ = s.Set("work", sampleCredential())
	if err := s.Delete("work"); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, err := s.Get("work"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("credential survived delete: %v", err)
	}
}

// Two profiles mutated concurrently must both survive. With a single shared
// file this loses one profile's rotated refresh token, which the server then
// treats as replay and answers by revoking the whole grant.
func TestEncFileConcurrentDifferentProfilesBothSurvive(t *testing.T) {
	s := testStore(t)

	var wg sync.WaitGroup
	for _, p := range []string{"work", "personal"} {
		wg.Add(1)
		go func(profile string) {
			defer wg.Done()
			c := sampleCredential()
			c.AccessToken = "access-token-" + profile + "-xxxxxxxx"
			if err := s.Set(profile, c); err != nil {
				t.Errorf("Set %s: %v", profile, err)
			}
		}(p)
	}
	wg.Wait()

	for _, p := range []string{"work", "personal"} {
		got, err := s.Get(p)
		if err != nil {
			t.Fatalf("profile %s was lost: %v", p, err)
		}
		if got.AccessToken != "access-token-"+p+"-xxxxxxxx" {
			t.Fatalf("profile %s holds the wrong credential: %q", p, got.AccessToken)
		}
	}
}

func TestFingerprintChangesWithTheAccessToken(t *testing.T) {
	a := sampleCredential()
	b := sampleCredential()
	b.AccessToken = "a-completely-different-token"
	if a.Fingerprint() == b.Fingerprint() {
		t.Fatal("fingerprint must track the access token")
	}
	if a.Fingerprint() != sampleCredential().Fingerprint() {
		t.Fatal("fingerprint must be stable for the same token")
	}
}
