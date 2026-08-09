package command

import "testing"

func TestValidateRejectsAnUnknownKey(t *testing.T) {
	spec := specFor(t, "search")
	// This is what a reverse-map bug produces. The server would ignore it and
	// return an unfiltered result, so the CLI is the only place it can be seen.
	err := ValidateArgs(spec, map[string]any{"query": "x", "realm_in": []string{"work"}})
	if err == nil {
		t.Fatal("an unknown top-level key must be rejected client-side")
	}
}

func TestValidateAcceptsAKnownKey(t *testing.T) {
	spec := specFor(t, "search")
	if err := ValidateArgs(spec, map[string]any{"query": "x", "limit": 5}); err != nil {
		t.Fatalf("valid arguments were rejected: %v", err)
	}
}

func TestValidateRequiresRequiredProperties(t *testing.T) {
	spec := specFor(t, "add_cell")
	err := ValidateArgs(spec, map[string]any{"content": "x"})
	if err == nil {
		t.Fatal("a missing required property must be a usage error")
	}
	if code := exitCodeFor(err); code != 2 {
		t.Fatalf("exit code = %d, want 2", code)
	}
}

func TestValidateChecksEnumValues(t *testing.T) {
	spec := specFor(t, "search")
	if err := ValidateArgs(spec, map[string]any{"profile": "nonsense"}); err == nil {
		t.Fatal("an out-of-enum value must be rejected")
	}
	if err := ValidateArgs(spec, map[string]any{"profile": "semantic"}); err != nil {
		t.Fatalf("a valid enum value was rejected: %v", err)
	}
}
