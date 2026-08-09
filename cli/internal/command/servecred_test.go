package command

import (
	"context"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/visterion/hivemem/cli/internal/keystore"
)

// countingSource records how often the store was read.
type countingSource struct {
	mu    sync.Mutex
	reads int
	cred  *keystore.Credential
	err   error
	// skew makes NeedsRefresh behave like auth.Manager's: true once the
	// credential is within a minute of expiry.
	skew time.Duration
}

func (c *countingSource) Credential(context.Context) (*keystore.Credential, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.reads++
	if c.err != nil {
		return nil, c.err
	}
	return c.cred, nil
}

func (c *countingSource) NeedsRefresh(cr *keystore.Credential) bool {
	if cr.RefreshToken == "" || cr.ExpiresAt == nil {
		return false
	}
	return cr.ExpiresAt.Before(time.Now().Add(c.skew))
}

func (c *countingSource) count() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reads
}

// Every frame used to re-read the store. On the encfile backend that is one
// Argon2id derivation at 64 MiB per frame, up to four concurrently; on Secret
// Service it is a D-Bus session per frame that is never closed.
func TestServeCredentialIsReadOnceForManyFrames(t *testing.T) {
	src := &countingSource{
		cred: &keystore.Credential{AccessToken: "tok-static-aaaaaa", TokenType: "Bearer"},
		skew: time.Minute,
	}
	credential, _ := newCachingCredential(src)

	var wg sync.WaitGroup
	for i := 0; i < 32; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			tok, err := credential(context.Background())
			if err != nil {
				t.Errorf("credential: %v", err)
			}
			if tok != "tok-static-aaaaaa" {
				t.Errorf("token = %q", tok)
			}
		}()
	}
	wg.Wait()

	if n := src.count(); n != 1 {
		t.Fatalf("the store was read %d times for 32 frames, want exactly 1", n)
	}
}

// The cache must not outlive the credential: once it enters the refresh window
// the next frame has to go back to the Manager, which is where the locked,
// proactive refresh happens.
func TestServeCredentialIsRereadInsideTheRefreshWindow(t *testing.T) {
	exp := time.Now().Add(30 * time.Second).UTC()
	src := &countingSource{
		cred: &keystore.Credential{
			AccessToken:  "tok-expiring-aaaa",
			RefreshToken: "refresh-aaaaaaaa",
			ExpiresAt:    &exp,
		},
		skew: time.Minute,
	}
	credential, _ := newCachingCredential(src)

	for i := 0; i < 3; i++ {
		if _, err := credential(context.Background()); err != nil {
			t.Fatalf("credential: %v", err)
		}
	}
	if n := src.count(); n != 3 {
		t.Fatalf("a credential inside the refresh window was read %d times, want 3", n)
	}
}

// Reload is the bridge's cool-down-expiry path. Serving it from cache would
// mean a long-running mcp-serve never notices a credential repaired in another
// process — the exact case Reload exists for.
func TestServeReloadAlwaysBypassesTheCache(t *testing.T) {
	src := &countingSource{
		cred: &keystore.Credential{AccessToken: "tok-first-aaaaaa", TokenType: "Bearer"},
		skew: time.Minute,
	}
	credential, reload := newCachingCredential(src)

	if _, err := credential(context.Background()); err != nil {
		t.Fatalf("credential: %v", err)
	}
	src.mu.Lock()
	src.cred = &keystore.Credential{AccessToken: "tok-repaired-aaa", TokenType: "Bearer"}
	src.mu.Unlock()

	got, err := reload(context.Background())
	if err != nil {
		t.Fatalf("reload: %v", err)
	}
	if got != "tok-repaired-aaa" {
		t.Fatalf("reload returned the cached token %q", got)
	}
	// And the repaired token is what subsequent frames see.
	next, err := credential(context.Background())
	if err != nil {
		t.Fatalf("credential after reload: %v", err)
	}
	if next != "tok-repaired-aaa" {
		t.Fatalf("post-reload frame got %q", next)
	}
	if n := src.count(); n != 2 {
		t.Fatalf("store reads = %d, want 2 (initial + reload)", n)
	}
}

// A failed re-read must drop the cached copy, or a logout or revoked grant
// stays invisible for the life of the process.
func TestServeCredentialDropsTheCacheWhenTheStoreFails(t *testing.T) {
	src := &countingSource{
		cred: &keystore.Credential{AccessToken: "tok-before-aaaaa", TokenType: "Bearer"},
		skew: time.Minute,
	}
	credential, reload := newCachingCredential(src)
	if _, err := credential(context.Background()); err != nil {
		t.Fatalf("credential: %v", err)
	}

	src.mu.Lock()
	src.err = keystore.ErrNotFound
	src.mu.Unlock()

	if _, err := reload(context.Background()); !errors.Is(err, keystore.ErrNotFound) {
		t.Fatalf("reload error = %v, want ErrNotFound", err)
	}
	if _, err := credential(context.Background()); !errors.Is(err, keystore.ErrNotFound) {
		t.Fatalf("the next frame was served from a stale cache: %v", err)
	}
}
