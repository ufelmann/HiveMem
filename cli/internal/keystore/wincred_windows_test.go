//go:build windows

package keystore

import (
	"strings"
	"testing"
)

func TestWinCredRoundTrip(t *testing.T) {
	store, _ := platformKeyring()
	profile := "hivemem-test-profile"
	t.Cleanup(func() { _ = store.Delete(profile) })

	want := sampleCredential()
	if err := store.Set(profile, want); err != nil {
		t.Fatalf("Set: %v", err)
	}
	got, err := store.Get(profile)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if got.AccessToken != want.AccessToken || got.ClientID != want.ClientID {
		t.Fatalf("round trip lost data: %+v", got)
	}
}

// Forces the DPAPI file fallback by exceeding the CredentialBlob limit. Without
// this the fallback path would never execute in CI.
func TestWinCredOversizeBlobFallsBackToDPAPIFile(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	store, _ := platformKeyring()
	profile := "hivemem-test-oversize"
	t.Cleanup(func() { _ = store.Delete(profile) })

	big := sampleCredential()
	big.AccessToken = strings.Repeat("a", credentialBlobLimit+512)

	if err := store.Set(profile, big); err != nil {
		t.Fatalf("Set oversize: %v", err)
	}
	got, err := store.Get(profile)
	if err != nil {
		t.Fatalf("Get oversize: %v", err)
	}
	if got.AccessToken != big.AccessToken {
		t.Fatal("oversize credential did not survive the DPAPI fallback")
	}
}
