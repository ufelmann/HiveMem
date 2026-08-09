package testsupport

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
)

// StubAS is a stand-in authorization server that reproduces the parts of the
// real one the CLI depends on — in particular exact redirect_uri matching and
// the client_id requirement on refresh.
type StubAS struct {
	*httptest.Server

	mu sync.Mutex
	// RegisteredRedirects maps client_id to its registered redirect URIs.
	RegisteredRedirects map[string][]string
	// RefreshCalls counts grant_type=refresh_token requests.
	RefreshCalls int
	// RefreshClientIDs records the client_id sent on each refresh, so a test
	// can assert it was present on every one.
	RefreshClientIDs []string
	// DisableOAuth makes every endpoint answer 404, the default server state.
	DisableOAuth bool
	// DisableRegistration makes /oauth/register answer 403.
	DisableRegistration bool
	// DropRefreshResponse simulates a lost response: the server rotates but the
	// client never learns the outcome.
	DropRefreshResponse bool
	// IssuerOverride publishes endpoints on a different origin (split-host).
	IssuerOverride string

	nextToken int
	rotated   map[string]bool
}

// NewStubAS starts the stub.
func NewStubAS() *StubAS {
	s := &StubAS{
		RegisteredRedirects: map[string][]string{},
		rotated:             map[string]bool{},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/oauth-authorization-server", s.discovery)
	mux.HandleFunc("/oauth/register", s.register)
	mux.HandleFunc("/oauth/authorize", s.authorize)
	mux.HandleFunc("/oauth/token", s.token)
	s.Server = httptest.NewServer(mux)
	return s
}

func (s *StubAS) issuer() string {
	if s.IssuerOverride != "" {
		return s.IssuerOverride
	}
	return s.URL
}

func (s *StubAS) discovery(w http.ResponseWriter, r *http.Request) {
	if s.DisableOAuth {
		w.WriteHeader(404)
		return
	}
	meta := map[string]any{
		"issuer":                 s.issuer(),
		"authorization_endpoint": s.issuer() + "/oauth/authorize",
		"token_endpoint":         s.issuer() + "/oauth/token",
	}
	// registration_endpoint is omitted when DCR is off, exactly as the real
	// discovery controller does.
	if !s.DisableRegistration {
		meta["registration_endpoint"] = s.issuer() + "/oauth/register"
	}
	writeJSON(w, 200, meta)
}

func (s *StubAS) register(w http.ResponseWriter, r *http.Request) {
	if s.DisableOAuth {
		w.WriteHeader(404)
		return
	}
	if s.DisableRegistration {
		writeJSON(w, 403, map[string]any{"error": "registration_disabled"})
		return
	}
	if ct := r.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/json") {
		writeJSON(w, 415, map[string]any{"error": "invalid_request",
			"error_description": "register consumes application/json"})
		return
	}
	var req struct {
		RedirectURIs []string `json:"redirect_uris"`
		ClientName   string   `json:"client_name"`
		Scope        string   `json:"scope"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)

	s.mu.Lock()
	s.nextToken++
	id := "client-" + itoa(s.nextToken)
	s.RegisteredRedirects[id] = req.RedirectURIs
	s.mu.Unlock()

	writeJSON(w, 201, map[string]any{
		"client_id": id, "redirect_uris": req.RedirectURIs,
		"token_endpoint_auth_method": "none", "scope": req.Scope,
	})
}

// authorize reproduces the exact-match check and immediately redirects with a
// code, standing in for a human clicking through the consent page.
func (s *StubAS) authorize(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	clientID, redirectURI, state := q.Get("client_id"), q.Get("redirect_uri"), q.Get("state")

	s.mu.Lock()
	registered, known := s.RegisteredRedirects[clientID]
	s.mu.Unlock()

	if !known {
		writeJSON(w, 400, map[string]any{"error": "invalid_client"})
		return
	}
	if !exactContains(registered, redirectURI) {
		writeJSON(w, 400, map[string]any{"error": "invalid_request",
			"error_description": "redirect_uri does not match a registered URI"})
		return
	}
	u, _ := url.Parse(redirectURI)
	rq := u.Query()
	rq.Set("code", "auth-code-1")
	rq.Set("state", state)
	u.RawQuery = rq.Encode()
	http.Redirect(w, r, u.String(), http.StatusFound)
}

func (s *StubAS) token(w http.ResponseWriter, r *http.Request) {
	if ct := r.Header.Get("Content-Type"); !strings.HasPrefix(ct, "application/x-www-form-urlencoded") {
		writeJSON(w, 415, map[string]any{"error": "invalid_request",
			"error_description": "token consumes application/x-www-form-urlencoded"})
		return
	}
	_ = r.ParseForm()
	grant := r.PostForm.Get("grant_type")
	clientID := r.PostForm.Get("client_id")

	if grant == "refresh_token" {
		s.mu.Lock()
		s.RefreshCalls++
		s.RefreshClientIDs = append(s.RefreshClientIDs, clientID)
		presented := r.PostForm.Get("refresh_token")
		alreadyRotated := s.rotated[presented]
		s.rotated[presented] = true
		s.mu.Unlock()

		if clientID == "" {
			writeJSON(w, 400, map[string]any{"error": "invalid_request",
				"error_description": "refresh_token, client_id required"})
			return
		}
		if alreadyRotated {
			// Replay: the real server chain-revokes here.
			writeJSON(w, 400, map[string]any{"error": "invalid_grant",
				"error_description": "refresh_token unknown, expired, or reused"})
			return
		}
		if s.DropRefreshResponse {
			// Rotated server-side, but the client never sees the answer. A
			// declared Content-Length the body does not deliver makes net/http's
			// own (well-synchronized) server teardown abort the connection once
			// the handler returns, so the client's read fails with an I/O error.
			// A raw Hijack()+Close(), and separately panic(http.ErrAbortHandler),
			// were both tried here first: both raced under `-race` between this
			// mutex-protected write and the caller's later unsynchronized read of
			// RefreshCalls/RefreshClientIDs, because neither path goes through
			// net/http's normal response-completion synchronization.
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Content-Length", "1000")
			w.WriteHeader(http.StatusOK)
			_, _ = w.Write([]byte("{"))
			return
		}
	}

	s.mu.Lock()
	s.nextToken++
	n := itoa(s.nextToken)
	s.mu.Unlock()

	writeJSON(w, 200, map[string]any{
		"access_token":  "access-" + n + "-xxxxxxxxxx",
		"refresh_token": "refresh-" + n + "-xxxxxxxxxx",
		"token_type":    "Bearer",
		"expires_in":    3600,
		"scope":         "read write",
	})
}

func exactContains(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var b []byte
	for n > 0 {
		b = append([]byte{byte('0' + n%10)}, b...)
		n /= 10
	}
	return string(b)
}
