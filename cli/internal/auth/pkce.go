package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
)

// PKCE holds one authorization request's proof-key material. S256 is mandatory
// on this server; there is no plain fallback.
type PKCE struct {
	Verifier  string
	Challenge string
	State     string
}

// NewPKCE generates a fresh verifier, its S256 challenge, and a random state.
func NewPKCE() (*PKCE, error) {
	verifier, err := randomURLSafe(64)
	if err != nil {
		return nil, fmt.Errorf("generate code verifier: %w", err)
	}
	state, err := randomURLSafe(24)
	if err != nil {
		return nil, fmt.Errorf("generate state: %w", err)
	}
	sum := sha256.Sum256([]byte(verifier))
	return &PKCE{
		Verifier:  verifier,
		Challenge: base64.RawURLEncoding.EncodeToString(sum[:]),
		State:     state,
	}, nil
}

func randomURLSafe(n int) (string, error) {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(b), nil
}
