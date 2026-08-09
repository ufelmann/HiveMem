package auth

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"sync"
	"time"

	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
)

const (
	// RefreshSkew is how far ahead of expiry a refresh fires. It is also the
	// only tolerance for local clock drift, since expires_at is derived
	// locally from the server's expires_in.
	RefreshSkew = 60 * time.Second

	// InFlightMaxAge bounds the latch. After this a retry is permitted — not
	// because the process probably died before sending (the marker is written
	// milliseconds before the POST, so the opposite is likelier), but because
	// the worst case of retrying is the same re-login the latch would force
	// forever.
	InFlightMaxAge = 5 * time.Minute
)

// ErrReloginRequired means the grant is gone or its state is unknown.
var ErrReloginRequired = errors.New("re-login required: run `hivemem login`")

// SetTokenEndpoint pins the token endpoint. Normally discovered; tests set it.
func (m *Manager) SetTokenEndpoint(u string) { m.tokenEndpoint = u }

// inProcess serializes refreshes inside one process, on top of the
// cross-process file lock. Ten concurrent bridge frames hitting expiry together
// must produce one token request, not ten.
var inProcess sync.Mutex

// Credential returns a usable credential, refreshing it first when it is
// within RefreshSkew of expiry.
func (m *Manager) Credential(ctx context.Context) (*keystore.Credential, error) {
	cred, err := m.store.Get(m.profile)
	if err != nil {
		return nil, err
	}
	if !m.needsRefresh(cred) {
		return cred, nil
	}

	inProcess.Lock()
	defer inProcess.Unlock()

	var out *keystore.Credential
	lockErr := config.WithLock("cred-"+m.profile, func() error {
		// Re-read under the lock: another process may have refreshed while we
		// were queued.
		fresh, err := m.store.Get(m.profile)
		if err != nil {
			return err
		}
		if !m.needsRefresh(fresh) {
			out = fresh
			return nil
		}
		refreshed, err := m.doRefresh(ctx, fresh)
		if err != nil {
			return err
		}
		out = refreshed
		return nil
	})
	if lockErr != nil {
		return nil, lockErr
	}
	return out, nil
}

// needsRefresh compares against the THRESHOLD, not against now. Comparing
// against now would be self-defeating: at expires_at - RefreshSkew the stored
// expiry is still in the future, so every process — including the first —
// would conclude someone else already refreshed, and nobody ever would.
func (m *Manager) needsRefresh(c *keystore.Credential) bool {
	if c.IsStatic() || c.ExpiresAt == nil {
		return false
	}
	return c.ExpiresAt.Before(time.Now().Add(RefreshSkew))
}

func (m *Manager) doRefresh(ctx context.Context, cred *keystore.Credential) (*keystore.Credential, error) {
	// A marker from a previous attempt means that attempt's outcome is
	// unknown. Re-presenting the same refresh token would look like replay and
	// revoke the entire chain, so refuse — unless the marker has aged out.
	if cred.RefreshInFlight != nil {
		if time.Since(cred.RefreshInFlight.At) < InFlightMaxAge {
			return nil, fmt.Errorf("%w: the outcome of a previous refresh is unknown",
				ErrReloginRequired)
		}
		// Aged out: clear it and permit exactly one retry.
		cred.RefreshInFlight = nil
	}

	if cred.ClientID == "" {
		return nil, fmt.Errorf("%w: the stored credential has no client_id", ErrReloginRequired)
	}

	// Mark before the request. The server revokes the presented token before
	// it answers, so a lost response must never be retried blindly.
	marked := *cred
	marked.RefreshInFlight = &keystore.InFlight{At: time.Now().UTC()}
	if err := m.store.Set(m.profile, &marked); err != nil {
		return nil, fmt.Errorf("mark refresh in flight: %w", err)
	}

	form := url.Values{}
	form.Set("grant_type", "refresh_token")
	form.Set("refresh_token", cred.RefreshToken)
	// Required on every refresh; its absence is answered invalid_request.
	form.Set("client_id", cred.ClientID)

	tok, err := postToken(ctx, m.tokenEndpoint, form)
	if err != nil {
		var oe *OAuthError
		if errors.As(err, &oe) && oe.RequiresRelogin() {
			return nil, fmt.Errorf("%w: %s", ErrReloginRequired, oe.Error())
		}
		// Transport failure: the marker stays, so the next attempt refuses to
		// re-present the same token.
		return nil, fmt.Errorf("refresh failed, outcome unknown: %w", err)
	}

	expires := time.Now().UTC().Add(time.Duration(tok.ExpiresIn) * time.Second)
	next := &keystore.Credential{
		AccessToken:  tok.AccessToken,
		RefreshToken: tok.RefreshToken,
		TokenType:    tok.TokenType,
		// Retained from the previous blob: the token response carries no
		// client_id, and the NEXT refresh requires it.
		ClientID:  cred.ClientID,
		Scope:     orDefault(tok.Scope, cred.Scope),
		ExpiresAt: &expires,
		// Explicitly absent: a marker left behind here makes refresh #2 demand
		// a re-login, invisibly.
		RefreshInFlight: nil,
	}
	next.Register()
	if err := m.store.Set(m.profile, next); err != nil {
		return nil, fmt.Errorf("store refreshed credential: %w", err)
	}
	return next, nil
}

func orDefault(v, fallback string) string {
	if v != "" {
		return v
	}
	return fallback
}
