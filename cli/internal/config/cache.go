package config

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"time"
)

// CacheKey identifies one cached tool set. tools/list is role-filtered by the
// server, so a single unkeyed cache would let a reader profile overwrite a
// writer profile's subcommands.
type CacheKey struct {
	ServerURL string `json:"server_url"`
	Profile   string `json:"profile"`
}

func (k CacheKey) String() string { return k.ServerURL + "|" + k.Profile }

// AuthFailure records that a probe for this credential returned 401, so the CLI
// stops probing and does not walk into the server's 5-failure IP ban.
type AuthFailure struct {
	CredentialFingerprint string    `json:"credential_fingerprint"`
	At                    time.Time `json:"at"`
}

// CacheEntry is one (server, profile) pair's cached state.
type CacheEntry struct {
	Tools           []json.RawMessage `json:"tools"`
	Role            string            `json:"role"`
	FetchedAt       time.Time         `json:"fetched_at"`
	LastAuthFailure *AuthFailure      `json:"last_auth_failure,omitempty"`
}

// Cache is the whole cache.json document.
type Cache struct {
	Entries map[string]*CacheEntry `json:"entries"`
}

func cachePath(dir string) string { return filepath.Join(dir, "cache.json") }

// LoadCache reads cache.json. Unlike config.toml, a malformed cache is
// discarded rather than fatal — it is derived state and can be re-fetched. The
// cost is one extra probe, which is cheaper than refusing to run.
func LoadCache() (*Cache, error) {
	dir, err := ConfigDir()
	if err != nil {
		return nil, err
	}
	empty := &Cache{Entries: map[string]*CacheEntry{}}

	data, err := os.ReadFile(cachePath(dir))
	if errors.Is(err, os.ErrNotExist) {
		return empty, nil
	}
	if err != nil {
		return empty, nil
	}
	var c Cache
	if err := json.Unmarshal(data, &c); err != nil {
		return empty, nil
	}
	if c.Entries == nil {
		c.Entries = map[string]*CacheEntry{}
	}
	return &c, nil
}

// Get returns the entry for key.
func (c *Cache) Get(key CacheKey) (*CacheEntry, bool) {
	e, ok := c.Entries[key.String()]
	return e, ok
}

// PutTools stores a tool set and the role observed alongside it, merging into
// any existing entry so that last_auth_failure survives a schema refresh.
// Passing an empty role nulls the recorded role rather than leaving a stale one
// beside a fresh tool set.
func (c *Cache) PutTools(key CacheKey, tools []json.RawMessage, role string) error {
	e, ok := c.Entries[key.String()]
	if !ok {
		e = &CacheEntry{}
		c.Entries[key.String()] = e
	}
	e.Tools = tools
	e.Role = role
	e.FetchedAt = time.Now().UTC()
	return c.save()
}

// PutAuthFailure sets or, with a nil f, clears the suppression record.
func (c *Cache) PutAuthFailure(key CacheKey, f *AuthFailure) error {
	e, ok := c.Entries[key.String()]
	if !ok {
		e = &CacheEntry{}
		c.Entries[key.String()] = e
	}
	e.LastAuthFailure = f
	return c.save()
}

// IsStale reports whether the entry is missing or older than maxAge.
func (c *Cache) IsStale(key CacheKey, maxAge time.Duration) bool {
	e, ok := c.Get(key)
	if !ok || e.FetchedAt.IsZero() {
		return true
	}
	return time.Since(e.FetchedAt) > maxAge
}

func (c *Cache) save() error {
	dir, err := ConfigDir()
	if err != nil {
		return err
	}
	data, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return err
	}
	return WithLock("cache", func() error {
		return WriteAtomic(cachePath(dir), data, 0o600)
	})
}
