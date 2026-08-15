package auth

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"sync"
)

// Loopback is the local redirect target for the authorization code. It binds
// FIRST so that the concrete port can go into the DCR request: the server
// compares redirect URIs byte-for-byte and has no loopback port exemption.
type Loopback struct {
	listener net.Listener
	srv      *http.Server
	result   chan loopbackResult
	once     sync.Once
	state    string
}

type loopbackResult struct {
	code string
	err  error
}

// Listen binds 127.0.0.1 on a free port. Always the literal 127.0.0.1 — never
// localhost or [::1], because the comparison is exact.
func Listen() (*Loopback, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return nil, fmt.Errorf("bind loopback listener: %w", err)
	}
	lb := &Loopback{listener: ln, result: make(chan loopbackResult, 1)}

	mux := http.NewServeMux()
	mux.HandleFunc("/callback", lb.handleCallback)
	lb.srv = &http.Server{Handler: mux}
	go func() { _ = lb.srv.Serve(ln) }()
	return lb, nil
}

// ExpectState pins the state value the callback must echo back.
func (l *Loopback) ExpectState(state string) { l.state = state }

// RedirectURI is the exact string that must be registered and later sent to
// both /oauth/authorize and /oauth/token.
func (l *Loopback) RedirectURI() string {
	return "http://" + l.listener.Addr().String() + "/callback"
}

func (l *Loopback) handleCallback(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	deliver := func(res loopbackResult) {
		l.once.Do(func() { l.result <- res })
	}

	if e := q.Get("error"); e != "" {
		desc := q.Get("error_description")
		http.Error(w, "Authorization failed: "+e, http.StatusBadRequest)
		deliver(loopbackResult{err: fmt.Errorf("authorization failed: %s %s", e, desc)})
		return
	}
	if l.state != "" && q.Get("state") != l.state {
		http.Error(w, "State mismatch", http.StatusBadRequest)
		deliver(loopbackResult{err: errors.New("state mismatch: the callback did not come from this login")})
		return
	}
	code := q.Get("code")
	if code == "" {
		http.Error(w, "No authorization code", http.StatusBadRequest)
		deliver(loopbackResult{err: errors.New("callback carried no authorization code")})
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = w.Write([]byte("<html><body><h1>Login complete</h1>" +
		"<p>You can close this tab and return to the terminal.</p></body></html>"))
	deliver(loopbackResult{code: code})
}

// Wait blocks until the callback arrives or ctx expires.
func (l *Loopback) Wait(ctx context.Context) (string, error) {
	select {
	case res := <-l.result:
		return res.code, res.err
	case <-ctx.Done():
		return "", fmt.Errorf("timed out waiting for the browser callback: %w", ctx.Err())
	}
}

// Close shuts the listener down.
func (l *Loopback) Close() { _ = l.srv.Close() }
