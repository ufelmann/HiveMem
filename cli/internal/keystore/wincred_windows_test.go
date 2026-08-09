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

// A credential that starts small (generic Credential Manager entry) and then
// grows past credentialBlobLimit (DPAPI file) must not leave the old generic
// entry behind to shadow the new one on every subsequent Get. A refresh that
// rotates to a longer token is the realistic trigger for this growth.
func TestWinCredGrowingBlobDoesNotLeaveStaleGenericEntry(t *testing.T) {
	t.Setenv("LOCALAPPDATA", t.TempDir())
	store, _ := platformKeyring()
	profile := "hivemem-test-growing"
	t.Cleanup(func() { _ = store.Delete(profile) })

	small := sampleCredential()
	small.AccessToken = "short-token"
	if err := store.Set(profile, small); err != nil {
		t.Fatalf("Set small: %v", err)
	}

	big := sampleCredential()
	big.AccessToken = strings.Repeat("a", credentialBlobLimit+512)
	if err := store.Set(profile, big); err != nil {
		t.Fatalf("Set big: %v", err)
	}

	got, err := store.Get(profile)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if got.AccessToken != big.AccessToken {
		t.Fatalf("Get returned the stale small credential instead of the grown one: %+v", got)
	}
}
