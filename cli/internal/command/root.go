// Package command builds the Cobra command tree: fixed commands plus the
// subcommands generated from the server's tool schemas.
package command

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"regexp"
	"time"

	"github.com/spf13/cobra"
	"github.com/spf13/pflag"

	"github.com/visterion/hivemem/cli/internal/auth"
	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/httplog"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/mcp"
	"github.com/visterion/hivemem/cli/internal/redact"
)

// errNoServerConfigured is returned by resolveDeps (and its overrides
// variant) when no server resolves from --server, HIVEMEM_SERVER, or the
// saved config. status, tools, every generated subcommand, and
// diagnoseUnrecognizedCommand all surface this exact error — reusing the
// value, not a second copy of the string, is what keeps them from drifting
// apart.
var errNoServerConfigured = errors.New(
	"no server configured: pass --server or run `hivemem login --server <url>`")

// FixedNames are the command names the CLI owns. A generated tool with one of
// these names is not registered as a subcommand; it stays reachable via
// `hivemem call <tool>`. `status` really does collide: it is both a fixed
// command and an MCP tool.
var FixedNames = []string{"login", "logout", "status", "tools", "call", "mcp-serve"}

// isFixedName reports whether name collides with a fixed command name. It is
// the single predicate both attachGenerated (which decides which generated
// tools become subcommands) and `tools` (which marks a shadowed tool instead
// of letting it silently disappear) rely on.
func isFixedName(name string) bool {
	for _, n := range FixedNames {
		if n == name {
			return true
		}
	}
	return false
}

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
}

var opts = &globalOpts{}

// forceKeystoreBackend pins the keystore backend for both resolveDeps and
// mcp-serve. Empty in production, which is auto-selection. Tests set it so the
// suite exercises one known backend on every platform: clearing
// DBUS_SESSION_BUS_ADDRESS forces encfile on Linux but is inert on Windows,
// where the DPAPI keyring is unconditionally available — an auto-selecting test
// would then look in wincred for a credential seeded into the encrypted file.
//
// It is also seeded from HIVEMEM_E2E_FORCE_BACKEND at process start. That is
// the ONLY way a separate process — the built binary execed by
// internal/e2e's subprocess test — can pin the backend, since it cannot reach
// this package-level variable directly the way an in-process test can. It is
// not documented as a user-facing flag: real users get auto-selection.
var forceKeystoreBackend = os.Getenv("HIVEMEM_E2E_FORCE_BACKEND")

// keystoreBackendOverride returns the effective backend pin: an in-process
// test assignment to forceKeystoreBackend always wins over the environment
// variable it was seeded from, so existing white-box tests that mutate the
// variable directly (see helpers_test.go's pinEncFileBackend) are unaffected
// by this env var existing at all. When forceKeystoreBackend was never set
// in-process, this re-reads HIVEMEM_E2E_FORCE_BACKEND live rather than
// relying solely on the package-init read: in the real subprocess-exec case
// the env var is present before the binary starts, so the init-time read
// already has it, but in-process tests set it via t.Setenv after package
// init has already run, and would otherwise never observe it.
func keystoreBackendOverride() string {
	if forceKeystoreBackend != "" {
		return forceKeystoreBackend
	}
	return os.Getenv("HIVEMEM_E2E_FORCE_BACKEND")
}

func newRootCmd() *cobra.Command {
	root := &cobra.Command{
		Use:           "hivemem",
		Short:         "HiveMem command-line client",
		SilenceUsage:  true,
		SilenceErrors: true,
		// Persistent so it runs for every subcommand, including the generated
		// ones, and before any RunE issues a request.
		PersistentPreRun: func(*cobra.Command, []string) {
			httplog.SetEnabled(opts.verbose)
		},
	}
	p := root.PersistentFlags()
	p.StringVar(&opts.server, "server", "", "HiveMem server URL")
	p.StringVar(&opts.credProfile, "cred-profile", "", "credential profile (or HIVEMEM_PROFILE)")
	p.BoolVar(&opts.asJSON, "json", false, "emit raw JSON instead of formatted output")
	p.BoolVar(&opts.verbose, "verbose", false,
		"dump HTTP requests and responses to stderr (secrets are redacted)")
	p.DurationVar(&opts.timeout, "timeout", 0, "override the request timeout")

	addFixedCommands(root)
	return root
}

// Execute builds the command tree, attaches the generated subcommands, and
// returns the process exit code.
func Execute() int {
	code, line := execute(os.Args[1:], os.Stdout)
	if line != "" {
		fmt.Fprintln(os.Stderr, line)
	}
	return code
}

// execute runs the command tree for args and returns the exit code together
// with the line Execute should print to stderr — "" when nothing should be
// printed. Split out from Execute so tests can drive the whole dispatch,
// including the unknown-command diagnosis below, without touching os.Args or
// the process's real stderr.
func execute(args []string, out io.Writer) (code int, errLine string) {
	root := newRootCmd()
	root.SetOut(out)
	root.SetErr(out)
	root.SetArgs(args)

	// Generated subcommands come from the cache and must never block startup:
	// a missing or unreadable cache simply means fewer subcommands until the
	// next `hivemem tools`.
	if cache, err := config.LoadCache(); err == nil {
		if cfg, err := config.LoadConfig(); err == nil {
			key := config.CacheKey{ServerURL: resolveServer(cfg), Profile: resolveProfile(cfg)}
			if entry, ok := cache.Get(key); ok {
				// `tools` independently re-derives which tools are not
				// subcommands by calling evaluateTool itself, rather than
				// consuming a value returned here. That is safe — not a
				// staleness risk — because FixedNames is a static,
				// compile-time list: evaluateTool returns the same verdict
				// for a given raw tool definition no matter when or where
				// it runs, so the two call sites cannot drift apart.
				attachGenerated(root, entry.Tools)
			}
		}
	}

	err := root.Execute()
	if err == nil {
		return 0, ""
	}
	if _, ok := unmatchedCommandName(err); ok {
		if diag, ok := diagnoseUnrecognizedCommand(root.Context(), args); ok {
			err = diag
		}
	}
	return exitCodeFor(err), errorLine(err)
}

// errorLine formats the line Execute prints to stderr for a failed run, or
// "" when nothing should be printed. An *exitError can carry an empty
// message on purpose — runStatus, newCallCmd and buildCommand all return one
// solely to smuggle a specific exit code out of a RunE, with the report
// already written to stdout — and cobra would otherwise print a bare
// "Error: " line for it.
func errorLine(err error) string {
	// Redacted: this is the last thing a user copies into a bug report.
	msg := redact.Apply(err.Error())
	if msg == "" {
		return ""
	}
	return "Error: " + msg
}

// unknownCommandPattern matches cobra's own "unknown command" error text
// (args.go's legacyArgs, pinned at cobra v1.10.2 in go.mod). If a future
// cobra bump changes the wording, the match simply fails and
// diagnoseUnrecognizedCommand is skipped — the original cobra error passes
// through unchanged, same as today.
var unknownCommandPattern = regexp.MustCompile(`^unknown command "([^"]*)" for "`)

// unmatchedCommandName extracts the attempted command name from an
// "unknown command" error, so execute can offer a more specific diagnosis
// before falling back to cobra's own message.
func unmatchedCommandName(err error) (string, bool) {
	m := unknownCommandPattern.FindStringSubmatch(err.Error())
	if m == nil {
		return "", false
	}
	return m[1], true
}

// diagnoseUnrecognizedCommand replaces cobra's generic "unknown command"
// error with the real blocker, for the common case where the typed name is
// actually a tool that simply has not become a subcommand yet:
//
//   - no server resolves at all (no --server, no HIVEMEM_SERVER, nothing
//     saved in the config) — reports the exact error resolveDeps itself
//     returns for this, the same one `status` and `tools` already surface,
//     so the three can never drift apart.
//   - no credential for the resolved profile — the tool cache is always
//     empty in that state, so the name cannot be a subcommand yet either.
//     Reports the same message and exit code `tools` already gives.
//   - a credential exists but the tool cache was never populated (nobody
//     has run `hivemem tools` yet, or the cache was cleared) — names the
//     command that fixes it.
//
// When a credential and a cache entry are both present, the name is left
// alone: it is either really unregistered — a genuine typo, which must not
// be swallowed — or, in principle, a name that IS in the cache but was
// skipped by evaluateTool. That second case cannot actually reach here: a
// fixed-command-name collision already matches the built-in subcommand
// before Find ever falls back to the root, and evaluateTool's other
// rejection reason (an unparseable schema) never yields a Name to compare
// against. So every remaining case is a real typo, and reports false to let
// the caller keep cobra's own error and exit code.
//
// args is the raw, unparsed argument list execute() was given. Cobra never
// parses --server/--cred-profile for an unmatched command — Find() fails
// before ParseFlags ever runs — so this re-derives them itself with a
// throwaway flag set that tolerates unknown flags, instead of reading the
// (still zero-valued) global opts.
func diagnoseUnrecognizedCommand(ctx context.Context, args []string) (error, bool) {
	server, profile := parseGlobalFlags(args)
	d, err := resolveDepsWithOverrides(ctx, server, profile)
	if err != nil {
		if errors.Is(err, errNoServerConfigured) {
			return err, true
		}
		return nil, false
	}
	if d.Client == nil {
		return authError("not logged in: run `hivemem login`"), true
	}
	if _, ok := d.Cache.Get(d.Manager.CacheKey()); !ok {
		return usageError(
			"the tool list has not been fetched yet: run `hivemem tools --refresh`"), true
	}
	return nil, false
}

// parseGlobalFlags picks --server and --cred-profile out of an arbitrary
// argument list. It ignores parse errors and unknown flags (including a
// generated flag with no known arity, like --query on an unregistered tool
// name) by design: this is a best-effort diagnosis, not a real dispatch, and
// must never fail the way the real flag parse would.
func parseGlobalFlags(args []string) (server, credProfile string) {
	fs := pflag.NewFlagSet("diagnose", pflag.ContinueOnError)
	fs.ParseErrorsWhitelist = pflag.ParseErrorsWhitelist{UnknownFlags: true}
	fs.StringVar(&server, "server", "", "")
	fs.StringVar(&credProfile, "cred-profile", "", "")
	_ = fs.Parse(args)
	return server, credProfile
}

func resolveServer(cfg *config.Config) string {
	return resolveServerOverride(cfg, opts.server)
}

func resolveServerOverride(cfg *config.Config, override string) string {
	if override != "" {
		return override
	}
	if v := os.Getenv("HIVEMEM_SERVER"); v != "" {
		return v
	}
	return cfg.ServerURL
}

func resolveProfile(cfg *config.Config) string {
	return resolveProfileOverride(cfg, opts.credProfile)
}

func resolveProfileOverride(cfg *config.Config, override string) string {
	if override != "" {
		return override
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
	return resolveDepsWithOverrides(ctx, opts.server, opts.credProfile)
}

// resolveDepsWithOverrides is resolveDeps with the --server/--cred-profile
// values passed explicitly rather than read from the global opts. Cobra's
// own dispatch never has to do this — by the time a RunE runs, ParseFlags
// already wrote the CLI's --server and --cred-profile into opts — but
// diagnoseUnrecognizedCommand runs after Find() failed to resolve a
// subcommand, before ParseFlags ever ran, so it has no populated opts to
// read and must supply the overrides itself.
func resolveDepsWithOverrides(ctx context.Context, serverOverride, profileOverride string) (*Deps, error) {
	cfg, err := config.LoadConfig()
	if err != nil {
		return nil, err
	}
	cache, err := config.LoadCache()
	if err != nil {
		return nil, err
	}
	profile := resolveProfileOverride(cfg, profileOverride)
	server := resolveServerOverride(cfg, serverOverride)
	if server == "" {
		return nil, errNoServerConfigured
	}

	store, err := keystore.Select(keystore.SelectOptions{
		Passphrase:       []byte(os.Getenv("HIVEMEM_PASSPHRASE")),
		PassphrasePrompt: promptPassphrase,
		ForceBackend:     keystoreBackendOverride(),
	})
	if err != nil {
		if errors.Is(err, keystore.ErrPassphraseRequired) {
			// Same mapping newServeCmd applies on the headless path: exit 3
			// with the keystore package's own message, which names
			// HIVEMEM_PASSPHRASE.
			return nil, &exitError{code: 3, msg: err.Error()}
		}
		return nil, err
	}

	m := auth.NewManager(store, cache, server, profile)
	d := &Deps{Manager: m, Cache: cache, Store: store}

	if cred, err := m.Credential(ctx); err == nil {
		d.Client = mcp.New(server, cred.AccessToken, timeouts())
	}
	return d, nil
}

func exitCodeFor(err error) int {
	var ec exitCoder
	if errors.As(err, &ec) {
		return ec.ExitCode()
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
