package command

import (
	"testing"

	"github.com/visterion/hivemem/cli/internal/auth"
	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
)

// pinEncFileBackend makes resolveDeps and mcp-serve select the encrypted-file
// keystore on every platform, for the length of one test.
//
// Clearing DBUS_SESSION_BUS_ADDRESS is not enough: it forces encfile on Linux
// but is inert on Windows, where platformKeyring() is unconditionally
// available. Without this pin the Windows CI leg would run these tests against
// wincred, where nothing seeded the credential they read.
func pinEncFileBackend(t *testing.T) {
	t.Helper()
	saved := forceKeystoreBackend
	forceKeystoreBackend = "encfile"
	t.Cleanup(func() { forceKeystoreBackend = saved })
}

func newTestDeps(t *testing.T, serverURL string, cred *keystore.Credential) *Deps {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	store := keystore.NewEncFile([]byte("test passphrase"))
	if err := store.Set("work", cred); err != nil {
		t.Fatalf("seed credential: %v", err)
	}
	cache, err := config.LoadCache()
	if err != nil {
		t.Fatalf("LoadCache: %v", err)
	}
	m := auth.NewManager(store, cache, serverURL, "work")
	return &Deps{Manager: m, Cache: cache, Store: store}
}
