## HiveMem 6.2.0

This release packages the post-6.1.0 work on the browser UI, session-based
authentication, and MCP schema polish into a versioned Docker release. It keeps
the 30-tool MCP surface and external embedding architecture from 6.1.0, but
adds a usable first-party web app flow on top of it.

### Highlights

- **Session-based web login and auth wall.**
  - Added `LoginController`, IP-based login rate limiting, and a dedicated
    `SessionAuthFilter`.
  - The UI can now authenticate with a secure session cookie instead of forcing
    a bearer-token prompt inside the app shell.
  - `/mcp` respects the session path as well, with regression coverage for the
    session-cookie flow.
- **Graph route and parallel graph view.**
  - Added the graph route shell, React force-graph bridge, shared detail-state
    integration, and follow-up fixes for sizing, hover cleanup, focus alignment,
    and failed-detail resets.
  - The result is a navigable graph view that sits alongside the knowledge UI
    instead of remaining a prototype-only surface.
- **MCP schema cleanup.**
  - Tool `inputSchema.properties` are now populated consistently, which matters
    for MCP clients that inspect schemas strictly.
- **Documentation and Docker usage clarified.**
  - README now distinguishes the rolling image
    `ghcr.io/ufelmann/hivemem:main` from semver release tags.
  - Test-count claims were de-hardcoded so the README stops drifting as the
    suite grows.

### Breaking Changes from 6.1.x

No new Flyway migrations or MCP tool-count changes were introduced after 6.1.0.
The MCP surface remains at 30 tools.

The notable behavioral change is in the web app auth flow:

- Browser users are now expected to go through the login/session flow rather
  than a token-only in-app dialog.

Token-based API access for MCP clients remains supported.

### Migration from 6.1.x

1. `docker exec hivemem hivemem-backup`
2. `docker pull ghcr.io/ufelmann/hivemem:6.2.0`
3. Restart the container with the new image tag.
4. If you deploy the browser UI, validate the login flow and session-cookie
   behavior in front of your reverse proxy.

### Docker

```bash
docker pull ghcr.io/ufelmann/hivemem:6.2.0
docker pull ghcr.io/ufelmann/hivemem-embeddings:6.2.0
```

Use `:main` only if you explicitly want the rolling branch build.

### Full Changelog

https://github.com/ufelmann/HiveMem/compare/v6.1.0...v6.2.0
