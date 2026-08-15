// Package httplog dumps HTTP exchanges for --verbose.
//
// Everything it prints goes through redact.Apply, because the bodies it sees
// are token responses and server error payloads. Request headers are
// deliberately never printed: the Authorization header is the single value
// that must not reach a pasted bug report, and redaction is a safety net, not
// a licence to print it in the first place.
package httplog

import (
	"fmt"
	"io"
	"os"
	"sync"
	"sync/atomic"

	"github.com/visterion/hivemem/cli/internal/redact"
)

// maxBody caps one dumped body. A tools/list response is ~100 kB of schemas,
// which would bury the exchange the user is actually looking at.
const maxBody = 2000

var (
	enabled atomic.Bool

	mu  sync.Mutex
	out io.Writer = os.Stderr
)

// SetEnabled turns dumping on or off. Off by default, so nothing is printed
// unless --verbose was passed.
func SetEnabled(v bool) { enabled.Store(v) }

// Enabled reports whether dumping is on.
func Enabled() bool { return enabled.Load() }

// SetOutput redirects the dump. Tests use it; production leaves it on stderr,
// which is never the MCP transport even under mcp-serve.
func SetOutput(w io.Writer) {
	mu.Lock()
	defer mu.Unlock()
	out = w
}

// Request logs one outgoing request.
func Request(method, url string, body []byte) {
	if !Enabled() {
		return
	}
	write(fmt.Sprintf("> %s %s\n%s\n", method, url, clip(body)))
}

// Response logs one response. Register any secret the body carries BEFORE
// calling this: redaction can only replace values it already knows.
func Response(status int, body []byte) {
	if !Enabled() {
		return
	}
	write(fmt.Sprintf("< HTTP %d\n%s\n", status, clip(body)))
}

func write(s string) {
	mu.Lock()
	defer mu.Unlock()
	_, _ = io.WriteString(out, s)
}

func clip(body []byte) string {
	s := redact.Apply(string(body))
	if len(s) > maxBody {
		return s[:maxBody] + "…"
	}
	return s
}
