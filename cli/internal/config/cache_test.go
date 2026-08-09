package config

import (
	"encoding/json"
	"testing"
	"time"
)

func newTestCache(t *testing.T) *Cache {
	t.Helper()
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	c, err := LoadCache()
	if err != nil {
		t.Fatalf("LoadCache: %v", err)
	}
	return c
}

func TestCacheIsKeyedByServerAndProfile(t *testing.T) {
	c := newTestCache(t)
	a := CacheKey{ServerURL: "https://a.example", Profile: "work"}
	b := CacheKey{ServerURL: "https://a.example", Profile: "personal"}

	if err := c.PutTools(a, []json.RawMessage{json.RawMessage(`{"name":"add_cell"}`)}, "writer"); err != nil {
		t.Fatalf("PutTools a: %v", err)
	}
	if err := c.PutTools(b, []json.RawMessage{json.RawMessage(`{"name":"search"}`)}, "reader"); err != nil {
		t.Fatalf("PutTools b: %v", err)
	}

	ea, ok := c.Get(a)
	if !ok || len(ea.Tools) != 1 || ea.Role != "writer" {
		t.Fatalf("profile a clobbered: %+v", ea)
	}
	eb, ok := c.Get(b)
	if !ok || eb.Role != "reader" {
		t.Fatalf("profile b clobbered: %+v", eb)
	}
}

func TestPutToolsPreservesLastAuthFailure(t *testing.T) {
	c := newTestCache(t)
	k := CacheKey{ServerURL: "https://a.example", Profile: "work"}

	if err := c.PutAuthFailure(k, &AuthFailure{CredentialFingerprint: "fp1", At: time.Now()}); err != nil {
		t.Fatalf("PutAuthFailure: %v", err)
	}
	// A schema refresh rewrites the same entry and must not drop the record.
	if err := c.PutTools(k, []json.RawMessage{json.RawMessage(`{"name":"search"}`)}, "reader"); err != nil {
		t.Fatalf("PutTools: %v", err)
	}

	e, ok := c.Get(k)
	if !ok {
		t.Fatal("entry vanished")
	}
	if e.LastAuthFailure == nil {
		t.Fatal("last_auth_failure was dropped by a tool-set write")
	}
	if e.LastAuthFailure.CredentialFingerprint != "fp1" {
		t.Fatalf("fingerprint = %q, want fp1", e.LastAuthFailure.CredentialFingerprint)
	}
}

func TestPutAuthFailureNilClearsTheRecord(t *testing.T) {
	c := newTestCache(t)
	k := CacheKey{ServerURL: "https://a.example", Profile: "work"}

	_ = c.PutAuthFailure(k, &AuthFailure{CredentialFingerprint: "fp1", At: time.Now()})
	if err := c.PutAuthFailure(k, nil); err != nil {
		t.Fatalf("clear: %v", err)
	}
	e, _ := c.Get(k)
	if e != nil && e.LastAuthFailure != nil {
		t.Fatal("record survived an explicit clear")
	}
}

func TestIsStaleUsesFetchedAt(t *testing.T) {
	c := newTestCache(t)
	k := CacheKey{ServerURL: "https://a.example", Profile: "work"}
	_ = c.PutTools(k, []json.RawMessage{json.RawMessage(`{}`)}, "writer")

	if c.IsStale(k, 24*time.Hour) {
		t.Fatal("a freshly written entry must not be stale")
	}

	e, _ := c.Get(k)
	e.FetchedAt = time.Now().Add(-25 * time.Hour)
	_ = c.save()

	if !c.IsStale(k, 24*time.Hour) {
		t.Fatal("a 25h-old entry must be stale at a 24h threshold")
	}
	if c.IsStale(k, 26*time.Hour) {
		t.Fatal("a 25h-old entry must not be stale at a 26h threshold")
	}
}

func TestCorruptCacheIsDiscardedNotFatal(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", dir)
	t.Setenv("XDG_DATA_HOME", t.TempDir())

	cd, _ := ConfigDir()
	if err := WriteAtomic(cachePath(cd), []byte("{not json"), 0o600); err != nil {
		t.Fatalf("seed corrupt cache: %v", err)
	}

	c, err := LoadCache()
	if err != nil {
		t.Fatalf("a corrupt cache must be discarded, not fatal: %v", err)
	}
	if _, ok := c.Get(CacheKey{ServerURL: "x", Profile: "y"}); ok {
		t.Fatal("discarded cache should be empty")
	}
}
