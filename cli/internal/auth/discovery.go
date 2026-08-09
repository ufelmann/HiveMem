package auth

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"
)

// ErrOAuthDisabled means the server has OAuth switched off — the default
// configuration. The only way in is `hivemem login --token`.
var ErrOAuthDisabled = errors.New(
	"this server has OAuth disabled; authenticate with `hivemem login --token` instead")

// ErrRegistrationDisabled means dynamic client registration is off.
var ErrRegistrationDisabled = errors.New(
	"this server does not allow dynamic client registration; use `hivemem login --token`")

// Metadata is the authorization-server metadata document. Its endpoints are
// built from the server's configured issuer, which in a split-host deployment
// is a different origin than the URL the CLI was pointed at — so these values
// are used verbatim rather than being reconstructed.
type Metadata struct {
	Issuer                string `json:"issuer"`
	AuthorizationEndpoint string `json:"authorization_endpoint"`
	TokenEndpoint         string `json:"token_endpoint"`
	RegistrationEndpoint  string `json:"registration_endpoint"`
}

// RequireRegistration reports whether DCR is available. A missing
// registration_endpoint is how the discovery document signals it is off, which
// is cheaper to detect here than by eating a 403.
func (m *Metadata) RequireRegistration() error {
	if m.RegistrationEndpoint == "" {
		return ErrRegistrationDisabled
	}
	return nil
}

// Discover fetches /.well-known/oauth-authorization-server.
func Discover(ctx context.Context, serverURL string) (*Metadata, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	url := strings.TrimRight(serverURL, "/") + "/.well-known/oauth-authorization-server"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("fetch discovery document: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusNotFound {
		return nil, ErrOAuthDisabled
	}
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("discovery returned HTTP %d", resp.StatusCode)
	}

	var m Metadata
	if err := json.NewDecoder(resp.Body).Decode(&m); err != nil {
		// A non-JSON body here is the same signal as a 404: this is not an
		// OAuth-enabled server.
		return nil, ErrOAuthDisabled
	}
	if m.TokenEndpoint == "" || m.AuthorizationEndpoint == "" {
		return nil, ErrOAuthDisabled
	}
	return &m, nil
}
