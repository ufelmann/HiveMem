package command

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/spf13/cobra"
)

func loadFixture(t *testing.T) []json.RawMessage {
	t.Helper()
	raw, err := os.ReadFile("testdata/tools.json")
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	var tools []json.RawMessage
	if err := json.Unmarshal(raw, &tools); err != nil {
		t.Fatalf("decode fixture: %v", err)
	}
	return tools
}

func specFor(t *testing.T, name string) *ToolSpec {
	t.Helper()
	for _, raw := range loadFixture(t) {
		spec, err := GenerateSpec(raw)
		if err != nil {
			t.Fatalf("GenerateSpec: %v", err)
		}
		if spec.Name == name {
			return spec
		}
	}
	t.Fatalf("tool %q not in the fixture", name)
	return nil
}

func flagNames(spec *ToolSpec) map[string]FlagSpec {
	out := map[string]FlagSpec{}
	for _, f := range spec.Flags {
		out[f.Flag] = f
	}
	return out
}

func TestScalarAndBooleanFlags(t *testing.T) {
	f := flagNames(specFor(t, "add_cell"))
	if f["content"].Type != "string" {
		t.Fatalf("content = %+v, want a string flag", f["content"])
	}
	if f["importance"].Type != "integer" {
		t.Fatalf("importance = %+v, want an integer flag", f["importance"])
	}
	if f["pending"].Type != "boolean" {
		t.Fatalf("pending = %+v, want a boolean flag", f["pending"])
	}
}

func TestUnderscoreBecomesDashAndRoundTrips(t *testing.T) {
	f := flagNames(specFor(t, "add_cell"))
	spec, ok := f["object-"]
	if !ok {
		t.Fatalf("object_ should generate --object-, got %v", f)
	}
	if spec.Property != "object_" {
		t.Fatalf("reverse map lost the property name: %+v", spec)
	}
}

func TestEnumsAreCarried(t *testing.T) {
	f := flagNames(specFor(t, "search"))
	if len(f["profile"].Enum) != 5 {
		t.Fatalf("profile enum = %v, want five values", f["profile"].Enum)
	}
}

func TestArraysAreRepeatable(t *testing.T) {
	f := flagNames(specFor(t, "search"))
	if f["include"].Type != "array" {
		t.Fatalf("include = %+v, want an array flag", f["include"])
	}
}

func TestNestedObjectsBecomeAJSONFlag(t *testing.T) {
	f := flagNames(specFor(t, "search"))
	spec, ok := f["where-json"]
	if !ok {
		t.Fatalf("where should generate --where-json, got %v", f)
	}
	if !spec.IsJSON || spec.Property != "where" {
		t.Fatalf("where-json = %+v", spec)
	}
}

// A flat --realm-in would emit a top-level key the handler never reads and the
// server never rejects: all realms searched, exit 0, looks correct.
func TestNoFlagIsGeneratedForANestedMember(t *testing.T) {
	f := flagNames(specFor(t, "search"))
	for _, forbidden := range []string{"realm-in", "realm_in", "realm"} {
		if _, ok := f[forbidden]; ok {
			t.Fatalf("--%s must not exist: it is a member of the nested where object", forbidden)
		}
	}
}

// search declares its own `profile` property. If --profile were a reserved
// global the weight preset would be unreachable.
func TestSearchKeepsItsOwnProfileFlag(t *testing.T) {
	f := flagNames(specFor(t, "search"))
	if _, ok := f["profile"]; !ok {
		t.Fatal("search must keep --profile for the weight preset")
	}
}

// Checked against the flags buildCommand actually registers, not against
// GenerateSpec's raw FlagSpec list: GenerateSpec does not filter (the
// "diagnose" fixture entry deliberately declares a "server" property, colliding
// with the reserved --server global), so the guarantee this test names only
// holds where buildCommand's skip is applied.
func TestNoGeneratedFlagCollidesWithAReservedGlobal(t *testing.T) {
	reserved := map[string]bool{}
	for _, r := range ReservedFlags {
		reserved[r] = true
	}
	for _, raw := range loadFixture(t) {
		spec, _ := GenerateSpec(raw)
		cmd := buildCommand(spec, reserved)
		for name := range reserved {
			if cmd.Flags().Lookup(name) != nil {
				t.Fatalf("tool %s registers --%s, which is a reserved global", spec.Name, name)
			}
		}
	}
}

// The "diagnose" fixture entry has a "server" property, which collides with
// the reserved --server global, alongside a "target" property, which does
// not. Losing the whole command (or its unrelated flags) to one colliding
// property would be its own regression, so both are checked, not just the
// absence of --server.
func TestReservedPropertyFlagIsSkippedButOtherFlagsSurviveAndTheCommandStillRegisters(t *testing.T) {
	root := newRootCmd()
	attachGenerated(root, loadFixture(t))

	cmd, _, err := root.Find([]string{"diagnose"})
	if err != nil {
		t.Fatalf("find diagnose: %v", err)
	}
	if cmd.Name() != "diagnose" {
		t.Fatalf("diagnose did not register as a subcommand, found %q instead", cmd.Name())
	}
	if cmd.Flags().Lookup("server") != nil {
		t.Fatal("--server must not be registered: it collides with the reserved global --server")
	}
	if cmd.Flags().Lookup("target") == nil {
		t.Fatal("--target must still be registered: one colliding property must not take its siblings down with it")
	}
}

// status is both a fixed command and an MCP tool. The fixed command wins; the
// tool stays reachable through `hivemem call status`.
func TestCollidingToolIsNotRegisteredAsASubcommand(t *testing.T) {
	root := newRootCmd()
	skipped := attachGenerated(root, loadFixture(t))

	found := false
	for _, s := range skipped {
		if s == "status" {
			found = true
		}
	}
	if !found {
		t.Fatalf("status should have been skipped, skipped = %v", skipped)
	}

	cmd, _, err := root.Find([]string{"status"})
	if err != nil {
		t.Fatalf("find status: %v", err)
	}
	if cmd.Flags().Lookup("probe") == nil {
		t.Fatal("hivemem status resolved to the generated tool, not the fixed command")
	}

	if c, _, _ := root.Find([]string{"search"}); c.Name() != "search" {
		t.Fatal("search should be registered as a generated subcommand")
	}
}

func TestGeneratedCommandsAreRegisteredOnce(t *testing.T) {
	root := newRootCmd()
	attachGenerated(root, loadFixture(t))
	seen := map[string]int{}
	for _, c := range root.Commands() {
		seen[c.Name()]++
	}
	for name, n := range seen {
		if n > 1 {
			t.Fatalf("command %q registered %d times", name, n)
		}
	}
	_ = cobra.Command{}
}
