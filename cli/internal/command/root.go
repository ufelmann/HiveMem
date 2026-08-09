// Package command builds the Cobra command tree: fixed commands plus the
// subcommands generated from the server's tool schemas.
package command

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"time"

	"github.com/spf13/cobra"

	"github.com/visterion/hivemem/cli/internal/auth"
	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/mcp"
)

// FixedNames are the command names the CLI owns. A generated tool with one of
// these names is not registered as a subcommand; it stays reachable via
// `hivemem call <tool>`. `status` really does collide: it is both a fixed
// command and an MCP tool.
var FixedNames = []string{"login", "logout", "status", "tools", "call", "mcp-serve"}

// ReservedFlags are global flag names no generated flag may take. Note that
// `profile` is deliberately absent: `search` declares a `profile` property (a
// weight preset), so the credential selector is `--cred-profile`.
var ReservedFlags = []string{"json", "verbose", "server", "cred-profile", "timeout", "help"}

type globalOpts struct {
	server      string
	credProfile string
	asJSON      bool
	verbose     bool
	timeout     time.Duration
}

// Deps are the resolved runtime dependencies of a command.
type Deps struct {
	Manager *auth.Manager
	Client  *mcp.Client
	Cache   *config.Cache
	Store   keystore.Store
	Opts    *globalOpts
}

var opts = &globalOpts{}

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "hivemem",
		Short:         "HiveMem command-line client",
		SilenceUsage:  true,
		SilenceErrors: true,
	}
	p := root.PersistentFlags()
	p.StringVar(&opts.server, "server", "", "HiveMem server URL")
	p.StringVar(&opts.credProfile, "cred-profile", "", "credential profile (or HIVEMEM_PROFILE)")
	p.BoolVar(&opts.asJSON, "json", false, "emit raw JSON instead of formatted output")
	p.BoolVar(&opts.verbose, "verbose", false, "log HTTP requests (secrets are redacted)")
	p.DurationVar(&opts.timeout, "timeout", 0, "override the request timeout")

	addFixedCommands(root)
	return root
}

// Execute builds the command tree, attaches the generated subcommands, and
// returns the process exit code.
func Execute() int {
	root := newRootCmd()

	// Generated subcommands come from the cache and must never block startup:
	// a missing or unreadable cache simply means fewer subcommands until the
	// next `hivemem tools`.
	if cache, err := config.LoadCache(); err == nil {
		if cfg, err := config.LoadConfig(); err == nil {
			key := config.CacheKey{ServerURL: resolveServer(cfg), Profile: resolveProfile(cfg)}
			if entry, ok := cache.Get(key); ok {
				attachGenerated(root, entry.Tools)
			}
		}
	}

	if err := root.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, "Error:", err)
		return exitCodeFor(err)
	}
	return 0
}

// attachGenerated registers subcommands generated from cached tool schemas.
//
// TODO(task-11): generate one Cobra subcommand per cached tool schema (a
// generated tool whose name collides with FixedNames must not be registered —
// it stays reachable only via `hivemem call <tool>`). Until that task lands,
// this is a deliberate no-op so Execute's wiring is already in place.
func attachGenerated(root *cobra.Command, tools []json.RawMessage) {
	_ = root
	_ = tools
}

func resolveServer(cfg *config.Config) string {
	if opts.server != "" {
		return opts.server
	}
	if v := os.Getenv("HIVEMEM_SERVER"); v != "" {
		return v
	}
	return cfg.ServerURL
}

func resolveProfile(cfg *config.Config) string {
	if opts.credProfile != "" {
		return opts.credProfile
	}
	if v := os.Getenv("HIVEMEM_PROFILE"); v != "" {
		return v
	}
	if cfg.ActiveProfile != "" {
		return cfg.ActiveProfile
	}
	return "default"
}

func timeouts() mcp.Timeouts {
	t := mcp.DefaultTimeouts()
	if opts.timeout > 0 {
		t.ToolCall, t.Metadata = opts.timeout, opts.timeout
	}
	return t
}

// resolveDeps builds the dependency set for a command run.
func resolveDeps(ctx context.Context) (*Deps, error) {
	cfg, err := config.LoadConfig()
	if err != nil {
		return nil, err
	}
	cache, err := config.LoadCache()
	if err != nil {
		return nil, err
	}
	profile := resolveProfile(cfg)
	server := resolveServer(cfg)
	if server == "" {
		return nil, fmt.Errorf("no server configured: pass --server or run `hivemem login --server <url>`")
	}

	store, err := keystore.Select(keystore.SelectOptions{
		Passphrase:       []byte(os.Getenv("HIVEMEM_PASSPHRASE")),
		PassphrasePrompt: promptPassphrase,
	})
	if err != nil {
		return nil, err
	}

	m := auth.NewManager(store, cache, server, profile)
	d := &Deps{Manager: m, Cache: cache, Store: store, Opts: opts}

	if cred, err := m.Credential(ctx); err == nil {
		d.Client = mcp.New(server, cred.AccessToken, timeouts())
	}
	return d, nil
}

func exitCodeFor(err error) int {
	if code, ok := err.(exitCoder); ok {
		return code.ExitCode()
	}
	return mcp.ExitCodeFor(err)
}

type exitCoder interface{ ExitCode() int }

// exitError carries an explicit exit code out of a command.
type exitError struct {
	code int
	msg  string
}

func (e *exitError) Error() string { return e.msg }
func (e *exitError) ExitCode() int { return e.code }

func usageError(format string, args ...any) error {
	return &exitError{code: 2, msg: fmt.Sprintf(format, args...)}
}

func authError(format string, args ...any) error {
	return &exitError{code: 3, msg: fmt.Sprintf(format, args...)}
}
