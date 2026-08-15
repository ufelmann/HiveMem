package auth

import (
	"crypto/sha256"
	"encoding/base64"
	"testing"
)

func TestPKCEChallengeIsS256OfTheVerifier(t *testing.T) {
	p, err := NewPKCE()
	if err != nil {
		t.Fatalf("NewPKCE: %v", err)
	}
	sum := sha256.Sum256([]byte(p.Verifier))
	want := base64.RawURLEncoding.EncodeToString(sum[:])
	if p.Challenge != want {
		t.Fatalf("challenge = %q, want %q", p.Challenge, want)
	}
}

func TestPKCEValuesAreUnique(t *testing.T) {
	a, _ := NewPKCE()
	b, _ := NewPKCE()
	if a.Verifier == b.Verifier || a.State == b.State {
		t.Fatal("PKCE values must not repeat across calls")
	}
}
