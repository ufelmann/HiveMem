package config

import (
	"encoding/json"
	"errors"
	"fmt"
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

	path := cachePath(dir)
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return empty, nil
	}
	if err != nil {
		// Read error (permission denied, I/O error, etc.) — emit diagnostic but stay open.
		fmt.Fprintf(os.Stderr, "warning: cannot read cache: %v\n", err)
		return empty, nil
	}
	var c Cache
	if err := json.Unmarshal(data, &c); err != nil {
		// Corrupt JSON — silently discard (it's derived state).
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
// Takes the lock for the entire read-mutate-write cycle to prevent lost updates
// from concurrent processes.
func (c *Cache) PutTools(key CacheKey, tools []json.RawMessage, role string) error {
	dir, err := ConfigDir()
	if err != nil {
		return err
	}

	return WithLock("cache", func() error {
		// Re-read from disk under the lock to see the latest state.
		latest := &Cache{Entries: map[string]*CacheEntry{}}
		path := cachePath(dir)

		data, err := os.ReadFile(path)
		if err == nil {
			// File exists, try to parse it.
			if err := json.Unmarshal(data, latest); err != nil {
				// Corrupt file, start fresh.
				latest.Entries = map[string]*CacheEntry{}
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			// Read error other than NotExist — fail the operation.
			return fmt.Errorf("read cache: %w", err)
		}
		// If file doesn't exist, latest.Entries is already empty.

		// Get or create entry in the freshly-read cache.
		e, ok := latest.Entries[key.String()]
		if !ok {
			e = &CacheEntry{}
			latest.Entries[key.String()] = e
		}

		// Preserve LastAuthFailure from the entry on disk.
		preservedFailure := e.LastAuthFailure

		// Apply the change.
		e.Tools = tools
		e.Role = role
		e.FetchedAt = time.Now().UTC()
		e.LastAuthFailure = preservedFailure

		// Write.
		marshalled, err := json.MarshalIndent(latest, "", "  ")
		if err != nil {
			return err
		}
		if err := WriteAtomic(path, marshalled, 0o600); err != nil {
			return err
		}

		// Update receiver to reflect what was written.
		*c = *latest
		return nil
	})
}

// PutAuthFailure sets or, with a nil f, clears the suppression record.
// Takes the lock for the entire read-mutate-write cycle to prevent lost updates
// from concurrent processes.
func (c *Cache) PutAuthFailure(key CacheKey, f *AuthFailure) error {
	dir, err := ConfigDir()
	if err != nil {
		return err
	}

	return WithLock("cache", func() error {
		// Re-read from disk under the lock to see the latest state.
		latest := &Cache{Entries: map[string]*CacheEntry{}}
		path := cachePath(dir)

		data, err := os.ReadFile(path)
		if err == nil {
			// File exists, try to parse it.
			if err := json.Unmarshal(data, latest); err != nil {
				// Corrupt file, start fresh.
				latest.Entries = map[string]*CacheEntry{}
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			// Read error other than NotExist — fail the operation.
			return fmt.Errorf("read cache: %w", err)
		}
		// If file doesn't exist, latest.Entries is already empty.

		// Get or create entry in the freshly-read cache.
		e, ok := latest.Entries[key.String()]
		if !ok {
			e = &CacheEntry{}
			latest.Entries[key.String()] = e
		}

		// Apply the change.
		e.LastAuthFailure = f

		// Write.
		marshalled, err := json.MarshalIndent(latest, "", "  ")
		if err != nil {
			return err
		}
		if err := WriteAtomic(path, marshalled, 0o600); err != nil {
			return err
		}

		// Update receiver to reflect what was written.
		*c = *latest
		return nil
	})
}

// IsStale reports whether the entry is missing or older than maxAge.
func (c *Cache) IsStale(key CacheKey, maxAge time.Duration) bool {
	e, ok := c.Get(key)
	if !ok || e.FetchedAt.IsZero() {
		return true
	}
	return time.Since(e.FetchedAt) > maxAge
}
