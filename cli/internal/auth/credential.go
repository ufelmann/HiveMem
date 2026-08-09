// Package auth owns the credential lifecycle: login, refresh, and logout.
package auth

import (
	"context"
	"encoding/json"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/mcp"
)

// Manager ties one profile's credential to one server.
type Manager struct {
	store         keystore.Store
	cache         *config.Cache
	serverURL     string
	profile       string
	authServerURL string
}

// NewManager returns a manager for (serverURL, profile).
func NewManager(store keystore.Store, cache *config.Cache, serverURL, profile string) *Manager {
	return &Manager{store: store, cache: cache, serverURL: serverURL, profile: profile}
}

// CacheKey is this manager's cache coordinate.
func (m *Manager) CacheKey() config.CacheKey {
	return config.CacheKey{ServerURL: m.serverURL, Profile: m.profile}
}

// Profile returns the credential profile name.
func (m *Manager) Profile() string { return m.profile }

// ServerURL returns the configured server.
func (m *Manager) ServerURL() string { return m.serverURL }

// Store exposes the backend, for `hivemem status`.
func (m *Manager) Store() keystore.Store { return m.store }

// Credential returns the stored credential, refreshing it first when it is
// within the refresh window. Task 9 replaces the body of this method.
func (m *Manager) Credential(ctx context.Context) (*keystore.Credential, error) {
	return m.store.Get(m.profile)
}

// clientFor builds an MCP client for a credential.
func (m *Manager) clientFor(c *keystore.Credential) *mcp.Client {
	return mcp.New(m.serverURL, c.AccessToken, mcp.DefaultTimeouts())
}

// recordTools writes a tool set and its paired role into the cache. Every
// tool-set write carries a role, or nulls it: a reader tool set beside a stale
// `writer` role makes the next role comparison report a change that already
// happened.
func (m *Manager) recordTools(tools []json.RawMessage, role string) error {
	return m.cache.PutTools(m.CacheKey(), tools, role)
}
