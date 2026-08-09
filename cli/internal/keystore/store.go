// Package keystore stores one credential blob per profile in the operating
// system's secret store, with an encrypted-file fallback for headless hosts.
package keystore

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"time"

	"github.com/visterion/hivemem/cli/internal/redact"
)

// ErrNotFound is returned when no credential is stored for a profile.
var ErrNotFound = errors.New("no credential stored for this profile")

// InFlight marks a refresh that was started but whose outcome is unknown.
type InFlight struct {
	At time.Time `json:"at"`
}

// Credential is the whole stored blob for one profile.
//
// ClientID is not a secret but belongs to the credential: the server requires
// it on every refresh, so it must be replaced atomically with the tokens.
// TokenEndpoint is here for the same reason: it is discovered once during
// login and is the only address the refresh grant may be presented to, so a
// later process — which runs no discovery — has nowhere else to get it from.
// Scope and TokenType are stored for diagnostics only — the effective role
// comes from a wake_up probe, never from the requested scope.
type Credential struct {
	AccessToken     string     `json:"access_token"`
	RefreshToken    string     `json:"refresh_token,omitempty"`
	TokenType       string     `json:"token_type"`
	ClientID        string     `json:"client_id,omitempty"`
	TokenEndpoint   string     `json:"token_endpoint,omitempty"`
	Scope           string     `json:"scope,omitempty"`
	ExpiresAt       *time.Time `json:"expires_at,omitempty"`
	RefreshInFlight *InFlight  `json:"refresh_in_flight,omitempty"`
}

// IsStatic reports whether this is a pasted bearer token with no refresh path.
func (c *Credential) IsStatic() bool { return c.RefreshToken == "" }

// Fingerprint identifies the credential without exposing it. The probe
// suppression uses it to decide whether the credential has changed.
func (c *Credential) Fingerprint() string {
	sum := sha256.Sum256([]byte(c.AccessToken))
	return hex.EncodeToString(sum[:])
}

// Register hands every secret field to the redactor. Call it the moment a
// credential is received — parsed from a token response or read from stdin —
// not when it is stored, or a --verbose run can dump a token that never
// reached the keystore.
func (c *Credential) Register() {
	redact.Register(c.AccessToken)
	if c.RefreshToken != "" {
		redact.Register(c.RefreshToken)
	}
}

// Store is the backend-independent credential storage contract. Locking is not
// a Store concern: it is a cross-process file lock in internal/config, so it
// covers every backend uniformly.
type Store interface {
	Get(profile string) (*Credential, error)
	Set(profile string, c *Credential) error
	Delete(profile string) error
	// Name identifies the backend for `hivemem status`.
	Name() string
}
