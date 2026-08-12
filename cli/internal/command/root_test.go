package command

import (
	"bytes"
	"context"
	"fmt"
	"os"
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
// DBUS_SESSION_BUS_ADDRESS is cleared to reproduce "headless host, no session
// bus" regardless of what is running on the machine executing the test, and
// pinEncFileBackend makes that hold on Windows too, where the DPAPI keyring is
// always available and the environment variable means nothing.
func TestResolveDepsMapsMissingPassphraseToExitThree(t *testing.T) {
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_DATA_HOME", t.TempDir())
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("HIVEMEM_PASSPHRASE", "")
	pinEncFileBackend(t)

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

func TestForceKeystoreBackendSeededFromEnv(t *testing.T) {
	t.Setenv("HIVEMEM_E2E_FORCE_BACKEND", "encfile")
	// forceKeystoreBackend is read once, at package init, from
	// HIVEMEM_E2E_FORCE_BACKEND — re-run that same read here rather than
	// depending on process init order relative to t.Setenv, which would
	// make this test flaky depending on test execution order.
	if got := os.Getenv("HIVEMEM_E2E_FORCE_BACKEND"); got != "encfile" {
		t.Fatalf("test setup broken: HIVEMEM_E2E_FORCE_BACKEND = %q", got)
	}
	if got := keystoreBackendOverride(); got != "encfile" {
		t.Fatalf("keystoreBackendOverride() = %q, want %q", got, "encfile")
	}
}

func TestForceKeystoreBackendInProcessOverrideWinsOverEnv(t *testing.T) {
	t.Setenv("HIVEMEM_E2E_FORCE_BACKEND", "encfile")
	saved := forceKeystoreBackend
	forceKeystoreBackend = "keyring"
	t.Cleanup(func() { forceKeystoreBackend = saved })
	if got := keystoreBackendOverride(); got != "keyring" {
		t.Fatalf("keystoreBackendOverride() = %q, want %q (in-process var must win)", got, "keyring")
	}
}
