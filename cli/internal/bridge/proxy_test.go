package bridge

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func runProxy(t *testing.T, cfg Config, input string) string {
	t.Helper()
	var out bytes.Buffer
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := New(cfg).Run(ctx, strings.NewReader(input), &out); err != nil {
		t.Fatalf("Run: %v", err)
	}
	return out.String()
}

func staticCred(token string) func(context.Context) (string, error) {
	return func(context.Context) (string, error) { return token, nil }
}

func TestProxyForwardsAndReturnsOneLinePerFrame(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			ID json.RawMessage `json:"id"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":` + string(req.ID) + `,"result":{}}`))
	}))
	defer srv.Close()

	out := runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-aaaaaaaa"), Workers: 1},
		`{"jsonrpc":"2.0","id":1,"method":"ping"}`+"\n"+
			`{"jsonrpc":"2.0","id":2,"method":"ping"}`+"\n")

	if n := strings.Count(strings.TrimSpace(out), "\n") + 1; n != 2 {
		t.Fatalf("got %d output lines, want 2:\n%s", n, out)
	}
}

func TestProxySetsTheRequiredHeaders(t *testing.T) {
	var ct, accept, auth string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		ct, accept, auth = r.Header.Get("Content-Type"), r.Header.Get("Accept"), r.Header.Get("Authorization")
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":1,"result":{}}`))
	}))
	defer srv.Close()

	runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-bbbbbbbb"), Workers: 1},
		`{"jsonrpc":"2.0","id":1,"method":"ping"}`+"\n")

	if !strings.HasPrefix(ct, "application/json") {
		t.Fatalf("Content-Type = %q — without it Spring answers 415 before the controller runs", ct)
	}
	if !strings.Contains(accept, "application/json") {
		t.Fatalf("Accept = %q", accept)
	}
	if auth != "Bearer t-bbbbbbbb" {
		t.Fatalf("Authorization = %q", auth)
	}
}

func TestProxyPinsTheProtocolVersionOnInitialize(t *testing.T) {
	var sent string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Params struct {
				ProtocolVersion string `json:"protocolVersion"`
			} `json:"params"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		sent = req.Params.ProtocolVersion
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":1,"result":{}}`))
	}))
	defer srv.Close()

	runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-cccccccc"), Workers: 1},
		`{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26"}}`+"\n")

	if sent != "2025-06-18" {
		t.Fatalf("protocolVersion = %q, want 2025-06-18", sent)
	}
}

// The server answers a bind failure with id null but a valid jsonrpc member,
// so a purely structural check forwards it and the client's id is never
// answered. The id comparison is what catches it.
func TestProxyRewritesAnIDNullBindFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":null,"error":{"code":-32600,"message":"Invalid Request"}}`))
	}))
	defer srv.Close()

	out := runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-dddddddd"), Workers: 1},
		`{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"bad":true}}`+"\n")

	var got struct {
		ID json.RawMessage `json:"id"`
	}
	if err := json.Unmarshal([]byte(strings.TrimSpace(out)), &got); err != nil {
		t.Fatalf("output is not valid JSON: %v\n%s", err, out)
	}
	if string(got.ID) != "7" {
		t.Fatalf("id = %s, want 7 — the client would hang on an unanswered id", got.ID)
	}
}

func TestProxyPassesThroughAJSONRPCErrorUnchanged(t *testing.T) {
	body := `{"jsonrpc":"2.0","id":3,"error":{"code":-32003,"message":"Tool not permitted: add_cell"}}`
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(403)
		_, _ = w.Write([]byte(body))
	}))
	defer srv.Close()

	out := runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-eeeeeeee"), Workers: 1},
		`{"jsonrpc":"2.0","id":3,"method":"tools/call"}`+"\n")

	if !strings.Contains(out, "-32003") || !strings.Contains(out, "Tool not permitted") {
		t.Fatalf("a valid -32003 frame must pass through unmodified, got:\n%s", out)
	}
}

func TestProxySynthesizesForNonJSONRPCBodies(t *testing.T) {
	cases := []struct {
		name   string
		status int
		body   string
	}{
		{"415", 415, `{"status":415,"error":"Unsupported Media Type"}`},
		{"429", 429, `{"status":429,"error":"Too Many Requests"}`},
		{"500 html", 500, `<html><body>Internal Server Error</body></html>`},
		{"404", 404, ``},
		{"bare 403", 403, `{"timestamp":"...","status":403,"error":"Forbidden"}`},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				w.WriteHeader(tc.status)
				_, _ = w.Write([]byte(tc.body))
			}))
			defer srv.Close()

			out := runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-ffffffff"), Workers: 1},
				`{"jsonrpc":"2.0","id":9,"method":"ping"}`+"\n")

			line := strings.TrimSpace(out)
			if strings.Contains(line, "<html>") {
				t.Fatalf("a raw body reached stdout:\n%s", out)
			}
			var got struct {
				JSONRPC string          `json:"jsonrpc"`
				ID      json.RawMessage `json:"id"`
			}
			if err := json.Unmarshal([]byte(line), &got); err != nil {
				t.Fatalf("output is not a JSON-RPC frame: %v\n%s", err, out)
			}
			if got.JSONRPC != "2.0" || string(got.ID) != "9" {
				t.Fatalf("synthesized frame = %s", line)
			}
		})
	}
}

func TestProxyAnswersABatchLocally(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		_, _ = w.Write([]byte(`{"jsonrpc":"2.0","id":null,"result":{}}`))
	}))
	defer srv.Close()

	out := runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-gggggggg"), Workers: 1},
		`[{"jsonrpc":"2.0","id":1,"method":"ping"},{"jsonrpc":"2.0","id":2,"method":"ping"}]`+"\n")

	if atomic.LoadInt32(&hits) != 0 {
		t.Fatal("a batch must be answered locally, not forwarded")
	}
	if n := strings.Count(strings.TrimSpace(out), "\n") + 1; n != 2 {
		t.Fatalf("got %d lines for a two-member batch, want 2:\n%s", n, out)
	}
}

func TestProxySuppressesOutputForIDLessFrames(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(202)
	}))
	defer srv.Close()

	out := runProxy(t, Config{ServerURL: srv.URL, Credential: staticCred("t-hhhhhhhh"), Workers: 1},
		`{"jsonrpc":"2.0","method":"notifications/initialized"}`+"\n")

	if strings.TrimSpace(out) != "" {
		t.Fatalf("an id-less frame must produce no output, got:\n%s", out)
	}
}

// Ten frames against a revoked static token must not produce ten requests:
// each 401 with an Authorization header counts toward the 5-failure ban, and
// the ban hits every client behind this address.
func TestProxyCoolDownAppliesToStaticTokens(t *testing.T) {
	var hits int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		atomic.AddInt32(&hits, 1)
		w.WriteHeader(401)
		_, _ = w.Write([]byte(`{"status":401,"error":"Unauthorized"}`))
	}))
	defer srv.Close()

	var in strings.Builder
	for i := 1; i <= 10; i++ {
		in.WriteString(`{"jsonrpc":"2.0","id":`)
		in.WriteString(itoa(i))
		in.WriteString(`,"method":"ping"}` + "\n")
	}

	out := runProxy(t, Config{
		ServerURL: srv.URL, Credential: staticCred("revoked-token-x"),
		Workers: 1, CoolDown: time.Minute,
	}, in.String())

	if n := atomic.LoadInt32(&hits); n > 2 {
		t.Fatalf("%d requests reached the server, want at most 2 — the ban lands at 5", n)
	}
	if n := strings.Count(strings.TrimSpace(out), "\n") + 1; n != 10 {
		t.Fatalf("got %d output lines, want 10 — every frame must be answered:\n%s", n, out)
	}
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
