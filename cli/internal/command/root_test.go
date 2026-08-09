package command

import (
	"bytes"
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
