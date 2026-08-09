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

// SetTokenEndpoint pins the token endpoint, overriding whatever the stored
// credential carries. Nothing in production calls this — the endpoint travels
// in the credential blob (see resolveTokenEndpoint); it exists so a test can
// aim the refresh at a stub without going through a login.
func (m *Manager) SetTokenEndpoint(u string) { m.tokenEndpoint = u }

// resolveTokenEndpoint answers where this credential's refresh grant may be
// presented.
//
// The credential is the authority: it was discovered at login time, and in a
// split-host deployment the authorization server is a different origin than
// the MCP server the CLI was pointed at, so it cannot be reconstructed. An
// explicit pin wins over it, and a credential stored before the endpoint was
// persisted falls back to re-running discovery rather than failing — that
// blob is otherwise unrefreshable and would force a needless re-login.
func (m *Manager) resolveTokenEndpoint(ctx context.Context, cred *keystore.Credential) (string, error) {
	if m.tokenEndpoint != "" {
		return m.tokenEndpoint, nil
	}
	if cred.TokenEndpoint != "" {
		return cred.TokenEndpoint, nil
	}
	meta, err := Discover(ctx, m.authURL())
	if err != nil {
		return "", fmt.Errorf("%w: the stored credential carries no token endpoint "+
			"and discovery failed: %v", ErrReloginRequired, err)
	}
	return meta.TokenEndpoint, nil
}

// inProcess holds one mutex per profile. It is not required for correctness:
// config.WithLock("cred-"+profile, …) already serializes refreshes for that
// profile, both across processes and within this one, since each call opens
// its own file descriptor and flock(2) alone would already be correct. What
// this buys instead is cost: without it, ten concurrent goroutines hitting
// expiry for the same profile would each open a file descriptor and block on
// a syscall-level lock; with it, they contend on cheap memory and only the
// winner touches the filesystem. Keyed per profile so a refresh for one
// profile (say "personal") never blocks a concurrent refresh for an unrelated
// one ("work") against a different auth server.
var (
	inProcessMu sync.Mutex
	inProcess   = map[string]*sync.Mutex{}
)

// inProcessLock returns (creating on first use) the in-process mutex for profile.
func inProcessLock(profile string) *sync.Mutex {
	inProcessMu.Lock()
	defer inProcessMu.Unlock()
	m, ok := inProcess[profile]
	if !ok {
		m = &sync.Mutex{}
		inProcess[profile] = m
	}
	return m
}

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

	lock := inProcessLock(m.profile)
	lock.Lock()
	defer lock.Unlock()

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

	// Resolved BEFORE the marker is written. Everything above and here is a
	// configuration-level failure that sends no request at all; latching the
	// profile for it would brand a perfectly good grant as
	// outcome-unknown and demand a re-login for a problem a re-login on the
	// same broken configuration cannot fix.
	endpoint, err := m.resolveTokenEndpoint(ctx, cred)
	if err != nil {
		return nil, err
	}
	if endpoint == "" {
		return nil, fmt.Errorf("%w: no token endpoint is known for this credential",
			ErrReloginRequired)
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

	tok, err := postToken(ctx, endpoint, form)
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
		// Retained from the previous blob: the token response carries neither
		// the client_id nor the token endpoint, and the NEXT refresh requires
		// both. The resolved endpoint is written back rather than the stored
		// one, so a blob that had to fall back to discovery carries it
		// afterwards.
		ClientID:      cred.ClientID,
		TokenEndpoint: endpoint,
		Scope:         orDefault(tok.Scope, cred.Scope),
		ExpiresAt:     &expires,
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
