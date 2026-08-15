package auth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/visterion/hivemem/cli/internal/httplog"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/redact"
)

// SetAuthServerURL overrides where discovery is fetched from. Defaults to the
// MCP server URL; tests point it at a stub.
func (m *Manager) SetAuthServerURL(u string) { m.authServerURL = u }

func (m *Manager) authURL() string {
	if m.authServerURL != "" {
		return m.authServerURL
	}
	return m.serverURL
}

// LoginWithOAuth runs discovery, binds the loopback listener, registers a
// fresh client for the bound port, drives the browser, exchanges the code, and
// stores the credential including its client_id.
//
// The order is not stylistic. Registering before binding cannot know the port,
// and reusing a stored client_id carries a dead one — both produce
// `400 invalid_request` at /oauth/authorize, because redirect URIs are matched
// by exact string equality.
func (m *Manager) LoginWithOAuth(ctx context.Context, openBrowser func(string) error) (string, error) {
	meta, err := Discover(ctx, m.authURL())
	if err != nil {
		return "", err
	}
	if err := meta.RequireRegistration(); err != nil {
		return "", err
	}

	// 1. Bind first.
	lb, err := Listen()
	if err != nil {
		return "", err
	}
	defer lb.Close()

	// 2. Register a fresh client carrying the concrete port.
	clientID, err := registerClient(ctx, meta.RegistrationEndpoint, lb.RedirectURI())
	if err != nil {
		return "", err
	}

	// 3. Drive the browser.
	pkce, err := NewPKCE()
	if err != nil {
		return "", err
	}
	lb.ExpectState(pkce.State)

	authURL := buildAuthorizeURL(meta.AuthorizationEndpoint, clientID, lb.RedirectURI(), pkce)
	if err := openBrowser(authURL); err != nil {
		return "", fmt.Errorf("open browser: %w (on a headless host use `hivemem login --token`)", err)
	}
	fmt.Fprintf(os.Stderr, "Opened %s\nWaiting for the browser to complete the login…\n",
		meta.AuthorizationEndpoint)

	waitCtx, cancel := context.WithTimeout(ctx, 5*time.Minute)
	defer cancel()
	code, err := lb.Wait(waitCtx)
	if err != nil {
		return "", fmt.Errorf("%w (on a headless host use `hivemem login --token`)", err)
	}

	// 4. Exchange the code, using the identical redirect URI string.
	tok, err := exchangeCode(ctx, meta.TokenEndpoint, clientID, lb.RedirectURI(), code, pkce.Verifier)
	if err != nil {
		return "", err
	}

	// 5. Store the credential INCLUDING client_id AND the discovered token
	//    endpoint — refresh is impossible without either, and no later process
	//    runs discovery.
	expires := time.Now().UTC().Add(time.Duration(tok.ExpiresIn) * time.Second)
	cred := &keystore.Credential{
		AccessToken:   tok.AccessToken,
		RefreshToken:  tok.RefreshToken,
		TokenType:     tok.TokenType,
		ClientID:      clientID,
		TokenEndpoint: meta.TokenEndpoint,
		Scope:         tok.Scope,
		ExpiresAt:     &expires,
	}
	cred.Register()
	if err := m.store.Set(m.profile, cred); err != nil {
		return "", fmt.Errorf("store credential: %w", err)
	}

	// 6. Fetch schemas and probe the role, so the cache entry is complete for
	//    both auth branches.
	client := m.clientFor(cred)
	tools, err := client.ListTools(ctx)
	if err != nil {
		return "", fmt.Errorf("fetch tool schemas: %w", err)
	}
	who, err := client.WakeUp(ctx)
	if err != nil {
		return "", fmt.Errorf("probe role: %w", err)
	}
	if err := m.recordTools(tools, who.Role); err != nil {
		return "", err
	}
	if err := m.cache.PutAuthFailure(m.CacheKey(), nil); err != nil {
		return "", err
	}
	return who.Role, nil
}

func registerClient(ctx context.Context, endpoint, redirectURI string) (string, error) {
	host, _ := os.Hostname()
	body, _ := json.Marshal(map[string]any{
		"redirect_uris": []string{redirectURI},
		// Named so the rows this leaves behind are identifiable for cleanup.
		"client_name": "hivemem-cli/" + host,
		// Sent explicitly rather than relying on the server's DCR default: a
		// future default change would otherwise yield a silently read-only
		// token whose write failures surface only as -32003.
		"scope":                      "read write",
		"grant_types":                []string{"authorization_code", "refresh_token"},
		"response_types":             []string{"code"},
		"token_endpoint_auth_method": "none",
	})

	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(string(body)))
	if err != nil {
		return "", err
	}
	// /oauth/register consumes JSON; /oauth/token does not.
	req.Header.Set("Content-Type", "application/json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("register client: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode == http.StatusForbidden {
		return "", ErrRegistrationDisabled
	}
	if resp.StatusCode >= 400 {
		raw, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("register client: HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(raw)))
	}
	var out struct {
		ClientID string `json:"client_id"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		return "", fmt.Errorf("decode registration response: %w", err)
	}
	if out.ClientID == "" {
		return "", fmt.Errorf("registration response carried no client_id")
	}
	return out.ClientID, nil
}

func buildAuthorizeURL(endpoint, clientID, redirectURI string, p *PKCE) string {
	q := url.Values{}
	q.Set("response_type", "code")
	q.Set("client_id", clientID)
	q.Set("redirect_uri", redirectURI)
	q.Set("scope", "read write")
	q.Set("state", p.State)
	q.Set("code_challenge", p.Challenge)
	q.Set("code_challenge_method", "S256")
	sep := "?"
	if strings.Contains(endpoint, "?") {
		sep = "&"
	}
	return endpoint + sep + q.Encode()
}

type tokenResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
	TokenType    string `json:"token_type"`
	ExpiresIn    int64  `json:"expires_in"`
	Scope        string `json:"scope"`
}

func exchangeCode(ctx context.Context, endpoint, clientID, redirectURI, code, verifier string) (*tokenResponse, error) {
	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("code", code)
	form.Set("client_id", clientID)
	form.Set("redirect_uri", redirectURI)
	form.Set("code_verifier", verifier)
	return postToken(ctx, endpoint, form)
}

// postToken posts a form-urlencoded body. This endpoint does not accept JSON.
func postToken(ctx context.Context, endpoint string, form url.Values) (*tokenResponse, error) {
	ctx, cancel := context.WithTimeout(ctx, 10*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	// The form carries the refresh token and the PKCE verifier, so it is dumped
	// only after redaction like everything else.
	httplog.Request(http.MethodPost, endpoint, []byte(form.Encode()))

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("token request: %w", err)
	}
	defer resp.Body.Close()

	raw, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		httplog.Response(resp.StatusCode, raw)
		var e struct {
			Error       string `json:"error"`
			Description string `json:"error_description"`
		}
		_ = json.Unmarshal(raw, &e)
		return nil, &OAuthError{Code: e.Error, Description: e.Description, HTTPStatus: resp.StatusCode}
	}
	var out tokenResponse
	if err := json.Unmarshal(raw, &out); err != nil {
		return nil, fmt.Errorf("decode token response: %w", err)
	}
	// Registered BEFORE the body is dumped. These tokens are brand new — the
	// redactor has never seen them — so dumping first would print the one thing
	// --verbose must never print. This is the case redact.Register exists for.
	redact.Register(out.AccessToken)
	redact.Register(out.RefreshToken)
	httplog.Response(resp.StatusCode, raw)
	return &out, nil
}

// OAuthError is an RFC 6749 error response.
type OAuthError struct {
	Code        string
	Description string
	HTTPStatus  int
}

func (e *OAuthError) Error() string {
	if e.Description != "" {
		return fmt.Sprintf("%s: %s", e.Code, e.Description)
	}
	return e.Code
}

// RequiresRelogin reports whether this error means the grant is gone. Both
// invalid_grant and invalid_request qualify: invalid_request is what a missing
// or malformed client_id produces, and treating it as unclassified would hide
// the one actionable message.
func (e *OAuthError) RequiresRelogin() bool {
	return e.Code == "invalid_grant" || e.Code == "invalid_request"
}

// httpGetFollow is a test helper that follows redirects, used to stand in for
// a browser.
func httpGetFollow(u string) (*http.Response, error) { return http.Get(u) }
