# HiveMem CLI

`hivemem` is a single-binary command-line client. It speaks the same MCP tool
surface as any other client, keeps its credentials in the operating system's
secret store, and can act as a local stdio MCP server so that no other client
needs to hold a token.

## Install

Download the binary for your platform from the latest release, or build it:

```bash
cd cli && make build      # ./bin/hivemem
```

Linux (amd64, arm64) and Windows (amd64) are supported. No runtime is required.

## Authenticate

```bash
hivemem login --server https://hivemem.example        # browser, OAuth + PKCE
hivemem login --server https://hivemem.example --token < token.txt
```

`--token` reads a static bearer token from **stdin** — never from an argument,
because a command line is visible to other processes and lands in shell
history.

Use `--token` on headless hosts: the OAuth flow needs a browser and a loopback
address on the same machine, and this server offers no device-code grant.

If the server has OAuth disabled — the default configuration — `login` says so
and points at `--token`.

## Where credentials live

| Platform | Backend |
|---|---|
| Windows | Credential Manager, DPAPI-encrypted against your account |
| Linux (desktop) | Secret Service — gnome-keyring, KWallet |
| Linux (headless) | AES-256-GCM file, `0600`, key derived from `HIVEMEM_PASSPHRASE` |

`hivemem status` names the active backend, the effective role, and the expiry.

The keystore protects against other user accounts and against reading the disk
at rest. It does not protect against code running as you — that code can read
any keyring you have unlocked.

## Use the tools

Every tool your role can call is a subcommand, generated from the server's own
schemas:

```bash
hivemem tools                        # list what this credential can call
hivemem search --query "deploy" --limit 5
hivemem search --where-json '{"realm":"work"}'
hivemem add_cell --content "…" --topic "…"
hivemem call <tool> --args-json '{…}'   # raw escape hatch
```

Nested objects take a JSON fragment (`--where-json`); `--json` switches the
*output* to raw JSON.

`call --args-json` is the escape hatch: it accepts keys the cached schema does
not describe, but a property the schema marks as required must still be
present. The server ignores keys it does not read rather than rejecting them,
so an incomplete payload would otherwise come back as a confident wrong answer.

## Seeing what goes over the wire

`--verbose` dumps every HTTP request and response body to **stderr**. Known
secrets — bearer tokens, refresh tokens, the values in a token response — are
replaced with `***`, and request headers are never printed at all. Output goes
to stderr, so it stays out of the way of `--json` and of `mcp-serve`'s
transport.

## As an MCP server

```json
{ "mcpServers": { "hivemem": { "command": "hivemem", "args": ["mcp-serve"] } } }
```

No token appears in that file. On a headless host set `HIVEMEM_PASSPHRASE` in
the environment: `mcp-serve` never prompts, because its stdin is the transport.

## Profiles

`--cred-profile <name>` or `HIVEMEM_PROFILE` selects a credential profile. It is
deliberately not called `--profile`, because `search` has a `profile` parameter
of its own.

## Exit codes

| Code | Meaning |
|---|---|
| 0 | success |
| 1 | unclassified failure |
| 2 | usage error |
| 3 | authentication or authorization |
| 4 | server-side (5xx, rate limit, embedding re-encode in progress) |
| 5 | the tool ran and failed |

## Logging out

`hivemem logout` deletes the local credential. This server has no revocation
endpoint, so an OAuth refresh token stays valid server-side until it expires or
an administrator revokes it.
