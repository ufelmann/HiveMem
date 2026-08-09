package command

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"

	"github.com/spf13/cobra"
	"golang.org/x/term"

	"github.com/visterion/hivemem/cli/internal/auth"
	"github.com/visterion/hivemem/cli/internal/bridge"
	"github.com/visterion/hivemem/cli/internal/config"
	"github.com/visterion/hivemem/cli/internal/keystore"
	"github.com/visterion/hivemem/cli/internal/mcp"
)

func addFixedCommands(root *cobra.Command) {
	root.AddCommand(newLoginCmd(), newLogoutCmd(), newStatusCmd(),
		newToolsCmd(), newCallCmd(), newServeCmd())
}

func newLoginCmd() *cobra.Command {
	var useToken bool
	cmd := &cobra.Command{
		Use:   "login",
		Short: "Authenticate against a HiveMem server",
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx := cmd.Context()
			d, err := resolveDeps(ctx)
			if err != nil {
				return err
			}
			var role string
			if useToken {
				// --token performs no discovery: discovery fails exactly on the
				// OAuth-disabled default configuration, where --token is the
				// only way in.
				token, err := readTokenFromStdin()
				if err != nil {
					return err
				}
				role, err = d.Manager.LoginWithToken(ctx, token)
				if err != nil {
					return authError("%v", err)
				}
			} else {
				role, err = d.Manager.LoginWithOAuth(ctx, openBrowser)
				if err != nil {
					if errors.Is(err, auth.ErrOAuthDisabled) ||
						errors.Is(err, auth.ErrRegistrationDisabled) {
						return &exitError{code: 3, msg: err.Error()}
					}
					return authError("%v", err)
				}
			}

			cfg, err := config.LoadConfig()
			if err != nil {
				return err
			}
			cfg.ServerURL = d.Manager.ServerURL()
			cfg.ActiveProfile = d.Manager.Profile()
			if err := cfg.Save(); err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(),
				"Logged in to %s as role %q (profile %q, credentials in %s)\n",
				d.Manager.ServerURL(), role, d.Manager.Profile(), d.Store.Name())
			return nil
		},
	}
	cmd.Flags().BoolVar(&useToken, "token", false, "read a static bearer token from stdin")
	return cmd
}

// readTokenFromStdin reads the token from stdin. It is never a command-line
// argument: /proc/<pid>/cmdline is readable by other processes, and argv also
// lands in shell history.
func readTokenFromStdin() (string, error) {
	if term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprint(os.Stderr, "Paste the token and press Enter: ")
	}
	r := bufio.NewReader(os.Stdin)
	line, err := r.ReadString('\n')
	if err != nil && err != io.EOF {
		return "", err
	}
	token := strings.TrimSpace(line)
	if token == "" {
		return "", usageError("no token was provided on stdin")
	}
	return token, nil
}

func newLogoutCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "logout",
		Short: "Delete the stored credential for this profile",
		RunE: func(cmd *cobra.Command, args []string) error {
			d, err := resolveDeps(cmd.Context())
			if err != nil {
				return err
			}
			if err := d.Manager.Logout(cmd.Context()); err != nil {
				return err
			}
			fmt.Fprintln(cmd.OutOrStdout(),
				"Local credential deleted. Note: this server has no revocation "+
					"endpoint, so the refresh token stays valid server-side until it "+
					"expires or an admin revokes it.")
			return nil
		},
	}
}

func newStatusCmd() *cobra.Command {
	var probe bool
	cmd := &cobra.Command{
		Use:   "status",
		Short: "Show the stored credential's backend, validity and role",
		RunE: func(cmd *cobra.Command, args []string) error {
			d, err := resolveDeps(cmd.Context())
			if err != nil {
				return err
			}
			if code := runStatus(cmd.Context(), d, cmd.OutOrStdout(), probe); code != 0 {
				return &exitError{code: code, msg: ""}
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&probe, "probe", false, "probe even if a previous probe returned 401")
	return cmd
}

// runStatus prints the credential report and returns an exit code.
func runStatus(ctx context.Context, d *Deps, out io.Writer, force bool) int {
	fmt.Fprintf(out, "Server:   %s\n", d.Manager.ServerURL())
	fmt.Fprintf(out, "Profile:  %s\n", d.Manager.Profile())
	fmt.Fprintf(out, "Keystore: %s\n", d.Store.Name())

	cred, err := d.Manager.Credential(ctx)
	if err != nil {
		if errors.Is(err, keystore.ErrNotFound) {
			if other, ok := keystore.OtherBackendHolds(d.Manager.Profile(), d.Store); ok {
				fmt.Fprintf(out, "Status:   not logged in here — a credential for this "+
					"profile exists in the %s backend\n", other)
			} else {
				fmt.Fprintln(out, "Status:   not logged in")
			}
			return 3
		}
		fmt.Fprintf(out, "Status:   %v\n", err)
		return 3
	}

	if cred.ExpiresAt != nil {
		fmt.Fprintf(out, "Expires:  %s\n", cred.ExpiresAt.Format(time.RFC3339))
	} else {
		fmt.Fprintln(out, "Expires:  never (static token)")
	}

	key := d.Manager.CacheKey()
	entry, _ := d.Cache.Get(key)
	if !force && entry != nil && entry.LastAuthFailure != nil &&
		entry.LastAuthFailure.CredentialFingerprint == cred.Fingerprint() &&
		time.Since(entry.LastAuthFailure.At) < 24*time.Hour {
		// Probing again would walk into the server's 5-failure IP ban, which
		// hits every client behind this address.
		fmt.Fprintf(out, "Status:   not probed — last probe returned 401 at %s "+
			"(use --probe to force)\n", entry.LastAuthFailure.At.Format(time.RFC3339))
		return 3
	}

	client := mcp.New(d.Manager.ServerURL(), cred.AccessToken, timeouts())
	who, err := client.WakeUp(ctx)
	if err != nil {
		var me *mcp.Error
		if errors.As(err, &me) && me.HTTPStatus == 401 {
			_ = d.Cache.PutAuthFailure(key, &config.AuthFailure{
				CredentialFingerprint: cred.Fingerprint(), At: time.Now().UTC(),
			})
			fmt.Fprintln(out, "Status:   the server rejected this credential (401)")
			return 3
		}
		if errors.As(err, &me) && me.HTTPStatus == 429 {
			wait := ""
			if me.RetryAfter != "" {
				wait = fmt.Sprintf(" — retry after %ss", me.RetryAfter)
			}
			fmt.Fprintf(out, "Status:   rate limited — this address is temporarily "+
				"banned%s\n", wait)
			return 4
		}
		fmt.Fprintf(out, "Status:   probe failed: %v\n", err)
		return mcp.ExitCodeFor(err)
	}

	// A probe that returns a result is evidence of repair.
	_ = d.Cache.PutAuthFailure(key, nil)
	fmt.Fprintf(out, "Identity: %s\n", who.Identity)
	fmt.Fprintf(out, "Role:     %s\n", who.Role)
	fmt.Fprintln(out, "Status:   ok")
	return 0
}

func newToolsCmd() *cobra.Command {
	var refresh bool
	cmd := &cobra.Command{
		Use:   "tools",
		Short: "List the tools this credential can call",
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx := cmd.Context()
			d, err := resolveDeps(ctx)
			if err != nil {
				return err
			}
			if d.Client == nil {
				return authError("not logged in: run `hivemem login`")
			}
			key := d.Manager.CacheKey()
			entry, ok := d.Cache.Get(key)

			if refresh || !ok || d.Cache.IsStale(key, 24*time.Hour) {
				tools, err := d.Client.ListTools(ctx)
				if err != nil {
					return err
				}
				// Every tool-set write carries a paired role probe, so the
				// recorded role never goes stale beside a fresh tool set — and
				// a probe that succeeds clears the 401 suppression.
				role := probeRole(ctx, d)
				if err := d.Cache.PutTools(key, tools, role); err != nil {
					return err
				}
				entry, _ = d.Cache.Get(key)
			}

			type toolListing struct {
				Name        string `json:"name"`
				Description string `json:"description"`
				// Shadowed is true when Name collides with a fixed command:
				// attachGenerated skipped it, so it was never registered as
				// a subcommand and is reachable only via `hivemem call`.
				Shadowed   bool   `json:"shadowed,omitempty"`
				ShadowedBy string `json:"shadowed_by,omitempty"`
			}
			var listings []toolListing
			for _, raw := range entry.Tools {
				var t struct {
					Name        string `json:"name"`
					Description string `json:"description"`
				}
				_ = json.Unmarshal(raw, &t)
				l := toolListing{Name: t.Name, Description: t.Description}
				if isFixedName(t.Name) {
					l.Shadowed = true
					l.ShadowedBy = "built-in command"
				}
				listings = append(listings, l)
			}

			if opts.asJSON {
				enc := json.NewEncoder(cmd.OutOrStdout())
				enc.SetIndent("", "  ")
				return enc.Encode(listings)
			}

			for _, l := range listings {
				line := fmt.Sprintf("%-28s %s", l.Name, firstLine(l.Description))
				if l.Shadowed {
					line += fmt.Sprintf(
						" (shadowed by the built-in command — call with: hivemem call %s)", l.Name)
				}
				fmt.Fprintln(cmd.OutOrStdout(), line)
			}
			return nil
		},
	}
	cmd.Flags().BoolVar(&refresh, "refresh", false, "re-fetch the tool schemas from the server")
	return cmd
}

func newCallCmd() *cobra.Command {
	var argsJSON string
	cmd := &cobra.Command{
		Use:   "call <tool>",
		Short: "Call a tool with a raw JSON argument object",
		Args:  cobra.ExactArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx := cmd.Context()
			d, err := resolveDeps(ctx)
			if err != nil {
				return err
			}
			if d.Client == nil {
				return authError("not logged in: run `hivemem login`")
			}
			payload := map[string]any{}
			if argsJSON != "" {
				if err := json.Unmarshal([]byte(argsJSON), &payload); err != nil {
					return usageError("--args-json is not valid JSON: %v", err)
				}
			}
			if entry, ok := d.Cache.Get(d.Manager.CacheKey()); ok {
				if spec := cachedSpec(entry.Tools, args[0]); spec != nil {
					if err := requireProperties(spec, payload); err != nil {
						return err
					}
				}
			}
			res, err := d.Client.CallTool(ctx, args[0], payload)
			if err != nil {
				return err
			}
			if err := Render(cmd.OutOrStdout(), res, opts.asJSON); err != nil {
				return err
			}
			if code := mcp.ExitCodeForResult(res); code != 0 {
				return &exitError{code: code, msg: ""}
			}
			return nil
		},
	}
	// --args-json, not --json: --json is the boolean output switch, and one
	// name cannot be both a boolean and a value-taking flag.
	cmd.Flags().StringVar(&argsJSON, "args-json", "", "raw JSON argument object")
	return cmd
}

// newServeCmd runs the CLI as a local MCP server that proxies stdio JSON-RPC
// to the configured HiveMem server. Its stdin is the JSON-RPC transport, so it
// must never prompt for a passphrase: PassphrasePrompt is deliberately left
// nil, unlike resolveDeps. On the encfile backend, a missing
// HIVEMEM_PASSPHRASE surfaces as keystore.ErrPassphraseRequired, which this
// command maps to exit 3 with a message naming the environment variable.
func newServeCmd() *cobra.Command {
	return &cobra.Command{
		Use:   "mcp-serve",
		Short: "Run as a local MCP server, proxying to the configured HiveMem server",
		RunE: func(cmd *cobra.Command, args []string) error {
			ctx := cmd.Context()

			cfg, err := config.LoadConfig()
			if err != nil {
				return err
			}
			cache, err := config.LoadCache()
			if err != nil {
				return err
			}
			profile := resolveProfile(cfg)
			server := resolveServer(cfg)
			if server == "" {
				return usageError("no server configured: pass --server or run `hivemem login --server <url>`")
			}

			store, err := keystore.Select(keystore.SelectOptions{
				Passphrase: []byte(os.Getenv("HIVEMEM_PASSPHRASE")),
				// No PassphrasePrompt: stdin here is the JSON-RPC transport.
				ForceBackend: forceKeystoreBackend,
			})
			if err != nil {
				if errors.Is(err, keystore.ErrPassphraseRequired) {
					return &exitError{code: 3, msg: err.Error()}
				}
				return err
			}

			m := auth.NewManager(store, cache, server, profile)

			// The bridge asks for a credential once per FRAME, and Reload is its
			// cool-down-expiry path: passing the same store-reading closure for
			// both is what made the cost proportional to traffic. The caching
			// pair keeps Reload's semantics — a static-token profile fixed via
			// `hivemem login` while this process kept running is still picked
			// up, and mcp-serve still never exits on its own for an auth
			// failure, which is the command path's behaviour, not this one's.
			credential, reload := newCachingCredential(m)
			p := bridge.New(bridge.Config{
				ServerURL:  server,
				Credential: credential,
				Reload:     reload,
				Workers:    4,
				CoolDown:   60 * time.Second,
			})
			return p.Run(ctx, cmd.InOrStdin(), cmd.OutOrStdout())
		},
	}
}

func firstLine(s string) string {
	if i := strings.IndexByte(s, '\n'); i >= 0 {
		return s[:i]
	}
	return s
}

// promptPassphrase reads the encfile passphrase from the terminal with no echo.
// Never called by mcp-serve: its stdin is the JSON-RPC transport.
func promptPassphrase() ([]byte, error) {
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		return nil, keystore.ErrPassphraseRequired
	}
	fmt.Fprint(os.Stderr, "Keystore passphrase: ")
	pw, err := term.ReadPassword(int(os.Stdin.Fd()))
	fmt.Fprintln(os.Stderr)
	return pw, err
}

// openBrowser launches the platform browser.
func openBrowser(url string) error {
	var cmd string
	var args []string
	switch runtime.GOOS {
	case "windows":
		cmd, args = "rundll32", []string{"url.dll,FileProtocolHandler", url}
	case "darwin":
		cmd, args = "open", []string{url}
	default:
		cmd, args = "xdg-open", []string{url}
	}
	return exec.Command(cmd, args...).Start()
}
