// Package redact scrubs known secret values out of any string that may reach a
// terminal, a log, or a bug report. Secrets are registered the moment they are
// received — not when they are stored — so that a token which never reaches the
// keystore (a fresh /oauth/token response under --verbose) is still covered.
package redact

import (
	"strings"
	"sync"
)

// minSecretLen guards against redacting short, common strings out of prose.
// Every credential this CLI handles is far longer.
const minSecretLen = 8

var (
	mu      sync.RWMutex
	secrets []string
)

// Register records a secret value. Safe to call from multiple goroutines and
// idempotent for repeated values.
func Register(secret string) {
	if len(secret) < minSecretLen {
		return
	}
	mu.Lock()
	defer mu.Unlock()
	for _, s := range secrets {
		if s == secret {
			return
		}
	}
	secrets = append(secrets, secret)
}

// Apply replaces every registered secret in s with ***.
func Apply(s string) string {
	mu.RLock()
	defer mu.RUnlock()
	for _, secret := range secrets {
		s = strings.ReplaceAll(s, secret, "***")
	}
	return s
}

// Wrap returns an error whose message has been redacted. Returns nil for nil.
func Wrap(err error) error {
	if err == nil {
		return nil
	}
	return redactedError{msg: Apply(err.Error())}
}

type redactedError struct{ msg string }

func (e redactedError) Error() string { return e.msg }

// reset clears registered secrets. Test-only.
func reset() {
	mu.Lock()
	defer mu.Unlock()
	secrets = nil
}
