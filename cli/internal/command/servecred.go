package command

import (
	"context"
	"sync"

	"github.com/visterion/hivemem/cli/internal/keystore"
)

// credentialSource is the slice of auth.Manager that mcp-serve's credential
// cache needs. An interface rather than the concrete type so the cache can be
// tested without a keystore, a server or a passphrase.
type credentialSource interface {
	Credential(ctx context.Context) (*keystore.Credential, error)
	NeedsRefresh(*keystore.Credential) bool
}

// newCachingCredential returns the (Credential, Reload) pair mcp-serve hands to
// the bridge.
//
// The bridge asks for a credential once per FRAME and dispatches up to Workers
// frames concurrently. Reading the store each time is not a cheap lookup: on
// the encrypted-file backend it re-derives an Argon2id key at 64 MiB of memory
// per read, four of them at once under load, and on Secret Service it opens a
// D-Bus session per read that is never closed. The spec holds the derived key
// for the process lifetime instead, which is what this restores.
//
// The cached copy is dropped exactly twice: when it enters the refresh window
// — the underlying Manager then performs its own locked, proactive refresh —
// and whenever Reload is called, which is the bridge's cool-down-expiry path.
// That path exists so a credential repaired in another process is picked up by
// a long-running mcp-serve, and it must therefore never be served from cache.
func newCachingCredential(src credentialSource) (credential, reload func(context.Context) (string, error)) {
	var mu sync.Mutex
	var cached *keystore.Credential

	// read must be called with mu held.
	read := func(ctx context.Context) (string, error) {
		c, err := src.Credential(ctx)
		if err != nil {
			// The stale copy is dropped: continuing to serve a token whose
			// re-read failed would hide a logout or a revoked grant for the
			// whole life of the process.
			cached = nil
			return "", err
		}
		cached = c
		return c.AccessToken, nil
	}

	credential = func(ctx context.Context) (string, error) {
		mu.Lock()
		defer mu.Unlock()
		if cached != nil && !src.NeedsRefresh(cached) {
			return cached.AccessToken, nil
		}
		return read(ctx)
	}
	reload = func(ctx context.Context) (string, error) {
		mu.Lock()
		defer mu.Unlock()
		return read(ctx)
	}
	return credential, reload
}
