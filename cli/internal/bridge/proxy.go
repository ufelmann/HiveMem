package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	"github.com/visterion/hivemem/cli/internal/redact"
)

// protocolVersion is what the bridge advertises upstream. 2025-06-18 removed
// batching, which this bridge answers locally.
const protocolVersion = "2025-06-18"

// Config configures a proxy.
type Config struct {
	ServerURL string
	// Credential returns the current bearer token, refreshing if due.
	Credential func(ctx context.Context) (string, error)
	// Reload re-reads the stored credential, bypassing any cached value. Used
	// when a cool-down expires: without it a long-running mcp-serve on a
	// static token never notices that the user fixed the credential elsewhere.
	Reload func(ctx context.Context) (string, error)
	// Workers bounds concurrent frame dispatch. Sequential processing would
	// let one slow tools/call head-of-line-block every other request.
	Workers int
	// CoolDown is how long an unresolvable 401 suppresses further attempts.
	CoolDown time.Duration
}

// Proxy forwards stdio JSON-RPC frames to /mcp.
type Proxy struct {
	cfg  Config
	http *http.Client

	mu            sync.Mutex
	coolDownUntil time.Time
	// lastFailedToken is the token that most recently produced an
	// unresolvable 401. Reload can return the same cached value it always
	// returns (e.g. a static-token Credential func with no real refresh
	// path); comparing against this catches that case and re-enters the
	// cool-down immediately instead of re-trying the request against the
	// server, which is what keeps ten pipelined frames on a revoked static
	// token down to at most two requests.
	lastFailedToken string
	// refreshing/refreshed collapse concurrent 401 resolutions into one, on
	// top of the cross-process file lock inside auth.Manager.
	refreshing bool
	refreshed  *sync.Cond
}

// New returns a proxy.
func New(cfg Config) *Proxy {
	if cfg.Workers <= 0 {
		cfg.Workers = 4
	}
	if cfg.CoolDown <= 0 {
		cfg.CoolDown = 60 * time.Second
	}
	p := &Proxy{cfg: cfg, http: &http.Client{Timeout: 60 * time.Second}}
	p.refreshed = sync.NewCond(&p.mu)
	return p
}

// Run reads frames until in is exhausted or ctx is cancelled.
func (p *Proxy) Run(ctx context.Context, in io.Reader, out io.Writer) error {
	reader := NewFrameReader(in)
	var writeMu sync.Mutex
	sem := make(chan struct{}, p.cfg.Workers)
	var wg sync.WaitGroup

	emit := func(line []byte) {
		writeMu.Lock()
		defer writeMu.Unlock()
		_, _ = out.Write(append(line, '\n'))
	}

	for {
		frame, err := reader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		if frame.ParseErr != nil {
			// Logged and skipped, never dropped silently. There is no id to
			// answer, so nothing goes to stdout.
			fmt.Fprintf(os.Stderr, "hivemem: skipping unusable input line: %v\n", frame.ParseErr)
			continue
		}
		if frame.IsBatch {
			// Answered locally: 2025-06-18 removed batching and the server
			// rejects it with id null, so forwarding would leave every
			// member id unanswered.
			for _, id := range frame.BatchIDs {
				emit(SynthesizeError(id, -32600,
					"JSON-RPC batching is not supported by this server"))
			}
			continue
		}

		f := frame
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			line := p.handle(ctx, f)
			// Suppression is decided from the OUTGOING request: no id means no
			// output line, whatever the server answered.
			if line != nil && f.HasID {
				emit(line)
			}
		}()
	}
	wg.Wait()
	return nil
}

func (p *Proxy) handle(ctx context.Context, f *Frame) []byte {
	body := f.Raw
	if f.Method == "initialize" {
		body = pinProtocolVersion(body)
	}

	// A 401 already in cool-down is answered directly, without a request:
	// the whole point of the cool-down is to keep an already-known-bad
	// credential from generating more failed, authenticated requests against
	// the server (each one counts toward the five-failure IP ban).
	if p.inCoolDown() {
		return SynthesizeError(f.ID, -32001, "not authenticated: run `hivemem login`")
	}

	token, err := p.initialToken(ctx)
	if err != nil {
		return SynthesizeError(f.ID, -32001, redact.Apply(err.Error()))
	}

	status, respBody, err := p.post(ctx, body, token)
	if err != nil {
		return SynthesizeError(f.ID, -32001, redact.Apply(err.Error()))
	}

	if status == http.StatusUnauthorized {
		// Recorded before resolve401 runs: resolve401 compares whatever
		// reload returns against THIS value, so a static-token profile —
		// whose reload always returns the same token — is caught on the
		// very first 401, not after a wasted retry.
		p.mu.Lock()
		p.lastFailedToken = token
		p.mu.Unlock()

		if retry, newToken := p.resolve401(ctx); retry {
			status, respBody, err = p.post(ctx, body, newToken)
			if err != nil {
				return SynthesizeError(f.ID, -32001, redact.Apply(err.Error()))
			}
		} else {
			return SynthesizeError(f.ID, -32001,
				"not authenticated: run `hivemem login`")
		}
	}

	return p.normalize(f, status, respBody)
}

// normalize decides between passing a body through and synthesizing.
//
// The test is structural AND id-based: the server answers a bind failure with
// id null while still carrying "jsonrpc", so a purely structural check would
// forward it and leave the client's id unanswered forever.
func (p *Proxy) normalize(f *Frame, status int, body []byte) []byte {
	var probe struct {
		JSONRPC string          `json:"jsonrpc"`
		ID      json.RawMessage `json:"id"`
	}
	if err := json.Unmarshal(body, &probe); err != nil || probe.JSONRPC == "" {
		return SynthesizeError(f.ID, codeForStatus(status),
			fmt.Sprintf("HTTP %d: %s", status, summarize(body)))
	}
	if !bytes.Equal(normalizeID(probe.ID), normalizeID(f.ID)) {
		// Same content, corrected id, so the client can match it.
		return rewriteID(body, f.ID)
	}
	return bytes.TrimRight(body, "\n")
}

func normalizeID(id json.RawMessage) []byte {
	if len(id) == 0 {
		return []byte("null")
	}
	return bytes.TrimSpace(id)
}

func rewriteID(body []byte, id json.RawMessage) []byte {
	var generic map[string]json.RawMessage
	if err := json.Unmarshal(body, &generic); err != nil {
		return SynthesizeError(id, -32603, "server response could not be re-keyed")
	}
	generic["id"] = normalizeID(id)
	out, err := json.Marshal(generic)
	if err != nil {
		return SynthesizeError(id, -32603, "server response could not be re-keyed")
	}
	return out
}

func (p *Proxy) post(ctx context.Context, body []byte, token string) (int, []byte, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		strings.TrimRight(p.cfg.ServerURL, "/")+"/mcp", bytes.NewReader(body))
	if err != nil {
		return 0, nil, err
	}
	// Content-Type is mandatory: without it Spring's @RequestBody binding
	// answers 415 before the controller runs.
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, text/event-stream")
	req.Header.Set("Authorization", "Bearer "+token)

	resp, err := p.http.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	return resp.StatusCode, raw, err
}

// inCoolDown reports whether an unresolvable 401 is still suppressing
// requests.
func (p *Proxy) inCoolDown() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return time.Now().Before(p.coolDownUntil)
}

// initialToken returns the token to use for a frame's first attempt. On
// cool-down expiry the credential is re-read (via Reload, bypassing whatever
// the plain Credential func would otherwise cache) before the coolDownUntil
// marker is cleared — otherwise a long-running mcp-serve would keep serving a
// stale cached token even after the user fixed the credential elsewhere.
func (p *Proxy) initialToken(ctx context.Context) (string, error) {
	p.mu.Lock()
	expired := !p.coolDownUntil.IsZero() && !time.Now().Before(p.coolDownUntil)
	if expired {
		p.coolDownUntil = time.Time{}
	}
	p.mu.Unlock()

	if expired {
		reload := p.cfg.Reload
		if reload == nil {
			reload = p.cfg.Credential
		}
		return reload(ctx)
	}
	return p.cfg.Credential(ctx)
}

// resolve401 decides whether a 401 can be retried. The caller has already
// recorded the token that failed into p.lastFailedToken before calling this.
//
// It covers BOTH a failed refresh and a static-token profile with no refresh
// path at all: a static Credential func has nothing to change on reload, so
// reload succeeding is not by itself evidence the credential is now good —
// only a *different* token is. Comparing the reloaded token against
// lastFailedToken (set by the caller for THIS failure, before reload ever
// runs) is what catches a static token on the very first 401 rather than
// after a wasted retry, keeping ten pipelined frames on a revoked static
// token down to at most two requests total.
func (p *Proxy) resolve401(ctx context.Context) (bool, string) {
	p.mu.Lock()
	if time.Now().Before(p.coolDownUntil) {
		p.mu.Unlock()
		return false, ""
	}
	for p.refreshing {
		p.refreshed.Wait()
	}
	// Another goroutine may have already resolved (or cooled down) this
	// exact failure while we were waiting.
	if time.Now().Before(p.coolDownUntil) {
		p.mu.Unlock()
		return false, ""
	}
	p.refreshing = true
	failedToken := p.lastFailedToken
	p.mu.Unlock()

	defer func() {
		p.mu.Lock()
		p.refreshing = false
		p.refreshed.Broadcast()
		p.mu.Unlock()
	}()

	reload := p.cfg.Reload
	if reload == nil {
		reload = p.cfg.Credential
	}
	token, err := reload(ctx)
	if err != nil || token == "" || token == failedToken {
		p.mu.Lock()
		p.coolDownUntil = time.Now().Add(p.cfg.CoolDown)
		p.mu.Unlock()
		return false, ""
	}

	return true, token
}

func pinProtocolVersion(body []byte) []byte {
	var msg map[string]json.RawMessage
	if err := json.Unmarshal(body, &msg); err != nil {
		return body
	}
	var params map[string]json.RawMessage
	if raw, ok := msg["params"]; ok {
		_ = json.Unmarshal(raw, &params)
	}
	if params == nil {
		params = map[string]json.RawMessage{}
	}
	params["protocolVersion"] = json.RawMessage(`"` + protocolVersion + `"`)
	if p, err := json.Marshal(params); err == nil {
		msg["params"] = p
	}
	out, err := json.Marshal(msg)
	if err != nil {
		return body
	}
	return out
}

func codeForStatus(status int) int {
	switch {
	case status == http.StatusTooManyRequests:
		return -32005
	case status >= 500:
		return -32003
	default:
		return -32001
	}
}

func summarize(body []byte) string {
	s := redact.Apply(strings.TrimSpace(string(body)))
	if len(s) > 200 {
		s = s[:200] + "…"
	}
	if s == "" {
		s = "(empty response body)"
	}
	return s
}
