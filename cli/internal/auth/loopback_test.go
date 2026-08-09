package auth

import (
	"context"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestLoopbackRedirectURIUses127001AndTheBoundPort(t *testing.T) {
	lb, err := Listen()
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer lb.Close()

	uri := lb.RedirectURI()
	if !strings.HasPrefix(uri, "http://127.0.0.1:") {
		t.Fatalf("redirect URI must use 127.0.0.1 literally, got %q", uri)
	}
	if !strings.HasSuffix(uri, "/callback") {
		t.Fatalf("redirect URI must end in /callback, got %q", uri)
	}
	if strings.Contains(uri, ":0/") {
		t.Fatalf("redirect URI must carry the bound port, got %q", uri)
	}
}

func TestLoopbackReturnsTheCode(t *testing.T) {
	lb, _ := Listen()
	defer lb.Close()
	lb.ExpectState("state-123")

	go func() {
		time.Sleep(20 * time.Millisecond)
		_, _ = http.Get(lb.RedirectURI() + "?code=abc123&state=state-123")
	}()

	code, err := lb.Wait(context.Background())
	if err != nil {
		t.Fatalf("Wait: %v", err)
	}
	if code != "abc123" {
		t.Fatalf("code = %q, want abc123", code)
	}
}

func TestLoopbackRejectsAStateMismatch(t *testing.T) {
	lb, _ := Listen()
	defer lb.Close()
	lb.ExpectState("state-123")

	go func() {
		time.Sleep(20 * time.Millisecond)
		_, _ = http.Get(lb.RedirectURI() + "?code=abc123&state=WRONG")
	}()

	if _, err := lb.Wait(context.Background()); err == nil {
		t.Fatal("a state mismatch must fail the login")
	}
}

func TestLoopbackSurfacesAnErrorParameter(t *testing.T) {
	lb, _ := Listen()
	defer lb.Close()
	lb.ExpectState("s")

	go func() {
		time.Sleep(20 * time.Millisecond)
		_, _ = http.Get(lb.RedirectURI() + "?error=access_denied&state=s")
	}()

	_, err := lb.Wait(context.Background())
	if err == nil || !strings.Contains(err.Error(), "access_denied") {
		t.Fatalf("want an access_denied error, got %v", err)
	}
}

func TestLoopbackTimesOutRatherThanHanging(t *testing.T) {
	lb, _ := Listen()
	defer lb.Close()
	lb.ExpectState("s")

	ctx, cancel := context.WithTimeout(context.Background(), 50*time.Millisecond)
	defer cancel()

	if _, err := lb.Wait(ctx); err == nil {
		t.Fatal("Wait must fail when the context expires")
	}
}
