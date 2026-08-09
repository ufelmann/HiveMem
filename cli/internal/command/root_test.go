package command

import (
	"bytes"
	"context"
	"fmt"
	"strings"
	"testing"
)

func TestCredProfileFlagParsesInBothArgumentOrders(t *testing.T) {
	for _, args := range [][]string{
		{"--cred-profile", "work", "status", "--help"},
		{"status", "--cred-profile", "work", "--help"},
	} {
		root := newRootCmd()
		root.SetOut(&bytes.Buffer{})
		root.SetErr(&bytes.Buffer{})
		root.SetArgs(args)
		if err := root.Execute(); err != nil {
			t.Fatalf("args %v: %v", args, err)
		}
	}
}

// --profile must stay free for the generated search flag, so the credential
// selector is deliberately named --cred-profile.
func TestProfileIsNotAReservedGlobal(t *testing.T) {
	for _, f := range ReservedFlags {
		if f == "profile" {
			t.Fatal("--profile must not be reserved; search declares its own profile property")
		}
	}
}

func TestReservedFlagsAreComplete(t *testing.T) {
	want := map[string]bool{"json": true, "verbose": true, "server": true,
		"cred-profile": true, "timeout": true, "help": true}
	if len(ReservedFlags) != len(want) {
		t.Fatalf("ReservedFlags = %v", ReservedFlags)
	}
	for _, f := range ReservedFlags {
		if !want[f] {
			t.Fatalf("unexpected reserved flag %q", f)
		}
	}
}

func TestFixedNamesAreComplete(t *testing.T) {
	want := "login,logout,status,tools,call,mcp-serve"
	if strings.Join(FixedNames, ",") != want {
		t.Fatalf("FixedNames = %v, want %s", FixedNames, want)
	}
}

// hivemem call takes its payload on --args-json: --json is the boolean output
// switch, and one name cannot be both on the same command.
func TestCallUsesArgsJSONNotJSON(t *testing.T) {
	root := newRootCmd()
	call, _, err := root.Find([]string{"call"})
	if err != nil {
		t.Fatalf("find call: %v", err)
	}
	if call.Flags().Lookup("args-json") == nil {
		t.Fatal("call must expose --args-json for its payload")
	}
	if f := call.Flags().Lookup("json"); f != nil && f.Value.Type() != "bool" {
		t.Fatal("--json must remain the boolean output switch on call")
	}
}

// commit 3232401 fixed the same bug shape in mcp.ExitCodeFor: a plain type
// assertion silently degrades a %w-wrapped exitCoder to exit 1. exitCodeFor
// must unwrap with errors.As, the same way.
func TestExitCodeForUnwrapsAWrappedExitError(t *testing.T) {
	base := &exitError{code: 3, msg: "credential rejected"}
	wrapped := fmt.Errorf("resolving deps: %w", base)
	if got := exitCodeFor(wrapped); got != 3 {
		t.Fatalf("exitCodeFor(wrapped) = %d, want 3", got)
	}
}

// resolveDeps must map a missing encfile passphrase to exit 3, the same way
// newServeCmd does, instead of letting it fall through unwrapped to exit 1.
// DBUS_SESSION_BUS_ADDRESS is cleared to force the encfile backend
// deterministically, reproducing "headless host, no session bus" regardless
// of what is actually running on the machine executing the test.
func TestResolveDepsMapsMissingPassphraseToExitThree(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "")

	saved := opts.server
	opts.server = "http://example.invalid"
	defer func() { opts.server = saved }()

	_, err := resolveDeps(context.Background())
	if err == nil {
		t.Fatal("resolveDeps succeeded, want an error for the missing passphrase")
	}
	if got := exitCodeFor(err); got != 3 {
		t.Fatalf("exitCodeFor(resolveDeps err) = %d, want 3 (err: %v)", got, err)
	}
	if !strings.Contains(err.Error(), "HIVEMEM_PASSPHRASE") {
		t.Fatalf("error must name HIVEMEM_PASSPHRASE, got: %v", err)
	}
}
