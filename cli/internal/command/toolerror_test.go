package command

import (
	"context"
	"encoding/json"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/mcp"
	"github.com/visterion/hivemem/cli/internal/testsupport"
)

// depsWithCachedTools wires a Deps against the fake server and seeds the cache
// with a tool set and the role observed beside it, the way any successful
// fetch would.
func depsWithCachedTools(t *testing.T, f *testsupport.FakeMCP, role string, names ...string) *Deps {
	t.Helper()
	cred := &keystore.Credential{AccessToken: "token-32003-aaaaaa", TokenType: "Bearer"}
	d := newTestDeps(t, f.URL, cred)
	d.Client = mcp.New(f.URL, cred.AccessToken, mcp.DefaultTimeouts())
	if err := d.Cache.PutTools(d.Manager.CacheKey(), toolList(names...), role); err != nil {
		t.Fatalf("seed cache: %v", err)
	}
	return d
}

func toolList(names ...string) []json.RawMessage {
	var out []json.RawMessage
	for _, n := range names {
		out = append(out, json.RawMessage(`{"name":"`+n+`","description":"d"}`))
	}
	return out
}

// ageCache pushes the entry's fetched_at into the past so the 60-second
// suppression gate in handleToolError sees a stale cache.
func ageCache(t *testing.T, d *Deps, age time.Duration) {
	t.Helper()
	e, ok := d.Cache.Get(d.Manager.CacheKey())
	if !ok {
		t.Fatal("no cache entry to age")
	}
	e.FetchedAt = time.Now().UTC().Add(-age)
}

// denyAndCall reproduces the production trigger: the server answers -32003 at
// HTTP 403 for a tool the permission gate refuses, which is the same answer it
// gives for a tool it no longer knows.
func denyAndCall(t *testing.T, f *testsupport.FakeMCP, d *Deps, tool string) error {
	t.Helper()
	f.DenyTool(tool)
	_, err := d.Client.CallTool(context.Background(), tool, nil)
	if err == nil {
		t.Fatal("the denied tool call must return an error")
	}
	var me *mcp.Error
	if !errors.As(err, &me) || !me.IsToolNotPermitted() {
		t.Fatalf("want a -32003 not-permitted error, got %#v", err)
	}
	return err
}

// Spec P1 case 1: a realm denial produces the identical -32003 a removed tool
// does, so a cache that was just written cannot be the explanation. Re-fetching
// on every denial would turn one realm-scoped call into three requests.
func TestDenialInsideTheSuppressionWindowDoesNotRefetch(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = toolList("search", "add_cell")

	d := depsWithCachedTools(t, f, "writer", "search", "add_cell")
	err := denyAndCall(t, f, d, "search")

	out := handleToolError(context.Background(), d, "search", err)
	if code := exitCodeFor(out); code != 3 {
		t.Fatalf("exit = %d, want 3 (a fresh cache means a genuine denial)", code)
	}
	if !strings.Contains(out.Error(), "Tool not permitted") {
		t.Fatalf("the denial must be reported verbatim, got: %v", out)
	}
	if n := f.MethodCount("tools/list"); n != 0 {
		t.Fatalf("tools/list was called %d times inside the 60 s window, want 0", n)
	}
}

// Spec P1 case 1, other leg: outside the window the cache IS a plausible
// explanation, so exactly one re-fetch happens — one tools/list plus the
// paired wake_up, because tools/list carries no role.
func TestDenialOutsideTheWindowRefetchesExactlyOnce(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = toolList("search", "add_cell")
	f.Role = "writer"

	d := depsWithCachedTools(t, f, "writer", "search", "add_cell")
	ageCache(t, d, 61*time.Second)
	err := denyAndCall(t, f, d, "search")

	out := handleToolError(context.Background(), d, "search", err)
	// The tool is still listed and the role is unchanged, so this is a genuine
	// denial (a realm scope) and the original message stands.
	if code := exitCodeFor(out); code != 3 {
		t.Fatalf("exit = %d, want 3", code)
	}
	if n := f.MethodCount("tools/list"); n != 1 {
		t.Fatalf("tools/list called %d times, want exactly 1", n)
	}
	if n := f.ToolCallCount("wake_up"); n != 1 {
		t.Fatalf("wake_up called %d times, want exactly 1 — without it the "+
			"role-reduction branch is dead code", n)
	}
}

// Spec P1 case 2, first branch: the tool is gone from the server. That is a
// usage error, not an auth one — retrying with different credentials cannot
// help.
func TestDenialForARemovedToolIsExitTwo(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	// The new listing no longer contains search.
	f.Tools = toolList("add_cell")
	f.Role = "writer"

	d := depsWithCachedTools(t, f, "writer", "search", "add_cell")
	ageCache(t, d, 61*time.Second)
	err := denyAndCall(t, f, d, "search")

	out := handleToolError(context.Background(), d, "search", err)
	if code := exitCodeFor(out); code != 2 {
		t.Fatalf("exit = %d, want 2 for a tool the server no longer offers", code)
	}
	if !strings.Contains(out.Error(), "no longer available on this server") {
		t.Fatalf("message = %q, want the removed-tool wording", out.Error())
	}
}

// Spec P1 case 2, second branch: the credential was demoted. That IS an auth
// problem, and the message must name the change — the bare "Tool not
// permitted" would send the user looking in the wrong place.
//
// The second half is the part that decides whether the write-back works: the
// new role must be persisted, or every subsequent denial re-announces a change
// that already happened.
func TestRoleReductionIsReportedOnceAndThenPersisted(t *testing.T) {
	f := testsupport.NewFakeMCP()
	defer f.Close()
	f.Tools = toolList("search", "add_cell")
	f.Role = "reader" // the server now reports a reduced role

	d := depsWithCachedTools(t, f, "writer", "search", "add_cell")
	ageCache(t, d, 61*time.Second)
	err := denyAndCall(t, f, d, "add_cell")

	out := handleToolError(context.Background(), d, "add_cell", err)
	if code := exitCodeFor(out); code != 3 {
		t.Fatalf("exit = %d, want 3 for a role reduction", code)
	}
	if !strings.Contains(out.Error(), "role changed from writer to reader") {
		t.Fatalf("message = %q, want the role-change wording", out.Error())
	}

	if e, _ := d.Cache.Get(d.Manager.CacheKey()); e == nil || e.Role != "reader" {
		t.Fatalf("the new role was not persisted: %+v", e)
	}

	// Second denial, same reduced role: the change is old news now.
	ageCache(t, d, 61*time.Second)
	_, err2 := d.Client.CallTool(context.Background(), "add_cell", nil)
	if err2 == nil {
		t.Fatal("the second denied call must still fail")
	}
	out2 := handleToolError(context.Background(), d, "add_cell", err2)
	if strings.Contains(out2.Error(), "role changed") {
		t.Fatalf("the role change was announced twice: %v", out2)
	}
	if code := exitCodeFor(out2); code != 3 {
		t.Fatalf("second denial exit = %d, want 3", code)
	}
}
