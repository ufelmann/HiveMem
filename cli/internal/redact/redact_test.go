package redact

import "strings"
import "testing"

func TestApplyReplacesRegisteredSecret(t *testing.T) {
	reset()
	Register("sk-secret-value-123")
	got := Apply("Authorization: Bearer sk-secret-value-123 failed")
	if strings.Contains(got, "sk-secret-value-123") {
		t.Fatalf("secret survived redaction: %q", got)
	}
	if !strings.Contains(got, "***") {
		t.Fatalf("expected a *** placeholder, got %q", got)
	}
}

func TestApplyIgnoresShortValues(t *testing.T) {
	reset()
	Register("abc") // too short to be a token; redacting it would mangle prose
	got := Apply("abc def")
	if got != "abc def" {
		t.Fatalf("short value should not be redacted, got %q", got)
	}
}

func TestRegisterIsConcurrencySafe(t *testing.T) {
	reset()
	done := make(chan struct{})
	for i := 0; i < 8; i++ {
		go func(n int) {
			Register(strings.Repeat("x", 20) + string(rune('a'+n)))
			_ = Apply("noise")
			close(make(chan struct{}))
			done <- struct{}{}
		}(i)
	}
	for i := 0; i < 8; i++ {
		<-done
	}
}
