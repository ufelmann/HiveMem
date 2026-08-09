package command

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/visterion/hivemem/cli/internal/mcp"
)

// ValidateArgs checks the argument object against the cached schema before any
// request is sent.
//
// This is not belt-and-braces: the server does NOT validate arguments against
// the schema, so an unknown or mis-mapped key is silently ignored rather than
// rejected. A reverse-map bug would therefore return a confident wrong answer.
// The CLI is the only place it can be caught.
func ValidateArgs(spec *ToolSpec, args map[string]any) error {
	for key := range args {
		if _, ok := spec.Properties[key]; !ok {
			return usageError("%s has no property %q", spec.Name, key)
		}
	}
	for _, req := range spec.Required {
		if _, ok := args[req]; !ok {
			return usageError("%s requires --%s", spec.Name, flagName(req))
		}
	}
	for key, val := range args {
		p := spec.Properties[key]
		if len(p.Enum) == 0 {
			continue
		}
		s, ok := val.(string)
		if !ok {
			continue
		}
		if !containsString(p.Enum, s) {
			return usageError("%s: %q is not one of %v", flagName(key), s, p.Enum)
		}
	}
	return nil
}

// handleToolError implements the cache-invalidation rules.
//
// -32003 is the trigger that fires in production. The permission gate runs
// before handler resolution, so a tool the server no longer knows is answered
// "not permitted", never "unknown tool". A realm denial produces the identical
// error, which is why the re-fetch is suppressed for a very fresh cache.
func handleToolError(ctx context.Context, d *Deps, tool string, err error) error {
	var me *mcp.Error
	if !errors.As(err, &me) {
		return err
	}
	key := d.Manager.CacheKey()

	switch {
	case me.IsToolNotPermitted():
		if !d.Cache.IsStale(key, 60*time.Second) {
			// Too fresh to be a stale-cache problem: report the denial.
			return &exitError{code: 3, msg: me.Message}
		}
		return d.refetchAndClassify(ctx, tool, me)

	case me.IsUnknownTool():
		// Defensive path: a permitted name with no handler. No age gate here.
		// Refresh the cache so the next invocation reflects reality, but
		// report the original error — issuing a second call with empty
		// arguments here would not be a retry of the user's call, it would
		// just be a different, meaningless request whose result we'd throw
		// away. There is nothing useful to do with it but surface err.
		if _, rerr := d.refetch(ctx); rerr != nil {
			return err
		}
		return err
	}
	return err
}

func (d *Deps) refetch(ctx context.Context) (string, error) {
	tools, err := d.Client.ListTools(ctx)
	if err != nil {
		return "", err
	}
	role := ""
	if who, werr := d.Client.WakeUp(ctx); werr == nil {
		role = who.Role
	}
	if err := d.Cache.PutTools(d.Manager.CacheKey(), tools, role); err != nil {
		return "", err
	}
	return role, nil
}

// refetchAndClassify decides between a removed tool, a role reduction and a
// genuine denial. tools/list carries no role, so the role comes from wake_up —
// without that second probe the role-reduction branch would be dead code.
func (d *Deps) refetchAndClassify(ctx context.Context, tool string, me *mcp.Error) error {
	before, _ := d.Cache.Get(d.Manager.CacheKey())
	oldRole := ""
	if before != nil {
		oldRole = before.Role
	}

	newRole, err := d.refetch(ctx)
	if err != nil {
		return &exitError{code: 3, msg: me.Message}
	}
	if oldRole != "" && newRole != "" && oldRole != newRole {
		return &exitError{code: 3, msg: fmt.Sprintf(
			"this credential's role changed from %s to %s, and %s is no longer available to it",
			oldRole, newRole, tool)}
	}

	after, _ := d.Cache.Get(d.Manager.CacheKey())
	if after != nil && !toolPresent(after.Tools, tool) {
		return &exitError{code: 2, msg: fmt.Sprintf(
			"%s is no longer available on this server", tool)}
	}
	return &exitError{code: 3, msg: me.Message}
}

// toolPresent decodes each cached tool's name and reports whether name is
// among them.
func toolPresent(tools []json.RawMessage, name string) bool {
	for _, raw := range tools {
		var t struct {
			Name string `json:"name"`
		}
		if err := json.Unmarshal(raw, &t); err == nil && t.Name == name {
			return true
		}
	}
	return false
}

func containsString(hay []string, needle string) bool {
	for _, h := range hay {
		if h == needle {
			return true
		}
	}
	return false
}
