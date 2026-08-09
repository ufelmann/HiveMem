package command

import (
	"encoding/json"
	"fmt"
	"strings"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"

	"github.com/visterion/hivemem/cli/internal/mcp"
)

// PropertySpec is one top-level schema property.
type PropertySpec struct {
	Type  string
	Enum  []string
	Items struct {
		Type string
		Enum []string
	}
}

// FlagSpec maps a command-line flag back to its exact JSON property name.
type FlagSpec struct {
	Flag     string
	Property string
	Type     string
	Enum     []string
	IsJSON   bool
	Help     string
}

// ToolSpec is one generated subcommand.
type ToolSpec struct {
	Name        string
	Description string
	Flags       []FlagSpec
	Required    []string
	Properties  map[string]PropertySpec
}

type rawTool struct {
	Name        string `json:"name"`
	Description string `json:"description"`
	InputSchema struct {
		Type       string                     `json:"type"`
		Required   []string                   `json:"required"`
		Properties map[string]json.RawMessage `json:"properties"`
	} `json:"inputSchema"`
}

type rawProperty struct {
	Type        string   `json:"type"`
	Description string   `json:"description"`
	Enum        []string `json:"enum"`
	Items       *struct {
		Type string   `json:"type"`
		Enum []string `json:"enum"`
	} `json:"items"`
}

// GenerateSpec turns one tool definition into a subcommand specification.
//
// Only TOP-LEVEL properties become flags. A member of a nested object never
// does: a flat --realm-in would emit a top-level key the handler never reads
// and the server never rejects, producing a confident wrong answer.
func GenerateSpec(raw json.RawMessage) (*ToolSpec, error) {
	var rt rawTool
	if err := json.Unmarshal(raw, &rt); err != nil {
		return nil, fmt.Errorf("decode tool definition: %w", err)
	}
	spec := &ToolSpec{
		Name:        rt.Name,
		Description: rt.Description,
		Required:    rt.InputSchema.Required,
		Properties:  map[string]PropertySpec{},
	}

	for name, propRaw := range rt.InputSchema.Properties {
		var p rawProperty
		if err := json.Unmarshal(propRaw, &p); err != nil {
			continue
		}
		ps := PropertySpec{Type: p.Type, Enum: p.Enum}
		if p.Items != nil {
			ps.Items.Type = p.Items.Type
			ps.Items.Enum = p.Items.Enum
		}
		spec.Properties[name] = ps

		fs := FlagSpec{Property: name, Help: p.Description, Enum: p.Enum}
		switch p.Type {
		case "string", "integer", "number", "boolean":
			fs.Flag, fs.Type = flagName(name), p.Type
		case "array":
			fs.Flag, fs.Type = flagName(name), "array"
			fs.Enum = ps.Items.Enum
		default:
			// object, or anything the table does not cover: a JSON fragment.
			// The generator is total — no property is ever silently omitted.
			fs.Flag, fs.Type, fs.IsJSON = flagName(name)+"-json", "json", true
		}
		spec.Flags = append(spec.Flags, fs)
	}
	return spec, nil
}

// flagName maps a JSON property to a flag: underscores become dashes. The
// reverse map on FlagSpec.Property is what makes the mapping safe — the
// inverse is only unambiguous while no property contains a literal dash.
func flagName(property string) string { return strings.ReplaceAll(property, "_", "-") }

// attachGenerated registers one subcommand per tool and returns the names it
// skipped because they collide with a fixed command.
func attachGenerated(root *cobra.Command, tools []json.RawMessage) []string {
	reserved := map[string]bool{}
	for _, f := range ReservedFlags {
		reserved[f] = true
	}

	var skipped []string
	for _, raw := range tools {
		spec, err := GenerateSpec(raw)
		if err != nil || spec.Name == "" {
			continue
		}
		if isFixedName(spec.Name) {
			// The fixed command wins; the tool stays reachable via `call`.
			skipped = append(skipped, spec.Name)
			continue
		}
		root.AddCommand(buildCommand(spec, reserved))
	}
	return skipped
}

func buildCommand(spec *ToolSpec, reserved map[string]bool) *cobra.Command {
	cmd := &cobra.Command{
		Use:   spec.Name,
		Short: firstLine(spec.Description),
	}
	fs := cmd.Flags()
	for _, f := range spec.Flags {
		if reserved[f.Flag] {
			// Guarded by a test; skipping keeps a future collision from
			// panicking at startup.
			continue
		}
		switch f.Type {
		case "boolean":
			fs.Bool(f.Flag, false, f.Help)
		case "integer":
			fs.Int64(f.Flag, 0, f.Help)
		case "number":
			fs.Float64(f.Flag, 0, f.Help)
		case "array":
			fs.StringArray(f.Flag, nil, f.Help)
		default:
			fs.String(f.Flag, "", f.Help)
		}
	}

	cmd.RunE = func(c *cobra.Command, args []string) error {
		ctx := c.Context()
		d, err := resolveDeps(ctx)
		if err != nil {
			return err
		}
		if d.Client == nil {
			return authError("not logged in: run `hivemem login`")
		}
		payload, err := BuildArgs(spec, c.Flags())
		if err != nil {
			return err
		}
		if err := ValidateArgs(spec, payload); err != nil {
			return err
		}
		res, err := d.Client.CallTool(ctx, spec.Name, payload)
		if err != nil {
			return handleToolError(ctx, d, spec.Name, err)
		}
		if err := Render(c.OutOrStdout(), res, opts.asJSON); err != nil {
			return err
		}
		if code := mcp.ExitCodeForResult(res); code != 0 {
			return &exitError{code: code, msg: ""}
		}
		return nil
	}
	return cmd
}

// BuildArgs turns the parsed flags into the JSON argument object, mapping each
// flag back to its exact property name.
func BuildArgs(spec *ToolSpec, fs *pflag.FlagSet) (map[string]any, error) {
	out := map[string]any{}
	for _, f := range spec.Flags {
		flag := fs.Lookup(f.Flag)
		if flag == nil || !flag.Changed {
			continue
		}
		switch f.Type {
		case "boolean":
			v, _ := fs.GetBool(f.Flag)
			out[f.Property] = v
		case "integer":
			v, _ := fs.GetInt64(f.Flag)
			out[f.Property] = v
		case "number":
			v, _ := fs.GetFloat64(f.Flag)
			out[f.Property] = v
		case "array":
			v, _ := fs.GetStringArray(f.Flag)
			out[f.Property] = v
		case "json":
			v, _ := fs.GetString(f.Flag)
			var parsed any
			if err := json.Unmarshal([]byte(v), &parsed); err != nil {
				return nil, usageError("--%s is not valid JSON: %v", f.Flag, err)
			}
			out[f.Property] = parsed
		default:
			v, _ := fs.GetString(f.Flag)
			out[f.Property] = v
		}
	}
	return out, nil
}
