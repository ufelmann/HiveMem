//go:build linux

package keystore

import (
	"os"
	"testing"
)

// Requires a session bus with a secrets service. CI runs this under
// `dbus-run-session` with `gnome-keyring-daemon --unlock`.
func TestSecretServiceRoundTrip(t *testing.T) {
	if os.Getenv("DBUS_SESSION_BUS_ADDRESS") == "" {
		t.Skip("no session bus; run under dbus-run-session")
	}
	store, ok := platformKeyring()
	if !ok {
		t.Skip("no Secret Service on this host")
	}

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

func TestPlatformKeyringUnavailableWithoutDBus(t *testing.T) {
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	if _, ok := platformKeyring(); ok {
		t.Fatal("without a session bus the keyring must report unavailable")
	}
}
