package command

import (
	"testing"

	"github.com/visterion/hivemem/cli/internal/auth"
	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
)

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
	return &Deps{Manager: m, Cache: cache, Store: store, Opts: &globalOpts{}}
}
