package auth

import (
	"context"
	"errors"
	"fmt"

	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/redact"
)

// LoginWithToken validates a pasted bearer token with one wake_up call and
// stores it only on success. wake_up rather than tools/list, because the role
// it returns is what goes into the cache; tools/list carries no role.
//
// The token is registered with the redactor before the first request, so a
// --verbose run cannot dump it even if validation fails.
func (m *Manager) LoginWithToken(ctx context.Context, token string) (string, error) {
	if token == "" {
		return "", errors.New("no token provided on stdin")
	}
	redact.Register(token)

	cred := &keystore.Credential{AccessToken: token, TokenType: "Bearer"}
	client := m.clientFor(cred)
	res, err := client.WakeUp(ctx)
	if err != nil {
		return "", fmt.Errorf("the token was rejected: %w", err)
	}

	if err := m.store.Set(m.profile, cred); err != nil {
		return "", fmt.Errorf("store credential: %w", err)
	}
	// The schemas are fetched here for the same reason the OAuth branch fetches
	// them: PutTools stamps fetched_at, so recording a nil tool set would leave
	// `hivemem tools` printing nothing — and generating no subcommands — for a
	// full 24 h, on the very path the headless documentation recommends.
	tools, err := client.ListTools(ctx)
	if err != nil {
		return "", fmt.Errorf("fetch tool schemas: %w", err)
	}
	if err := m.recordTools(tools, res.Role); err != nil {
		return "", fmt.Errorf("record role: %w", err)
	}
	// A fresh credential invalidates any suppression from the previous one.
	if err := m.cache.PutAuthFailure(m.CacheKey(), nil); err != nil {
		return "", fmt.Errorf("clear probe suppression: %w", err)
	}
	return res.Role, nil
}

// Logout deletes the stored credential. It is local only: this server exposes
// no revocation endpoint, so the refresh token stays valid server-side for its
// full 30-day TTL until an admin intervenes. The caller prints that.
func (m *Manager) Logout(ctx context.Context) error {
	if err := m.store.Delete(m.profile); err != nil {
		return err
	}
	return m.cache.PutAuthFailure(m.CacheKey(), nil)
}
