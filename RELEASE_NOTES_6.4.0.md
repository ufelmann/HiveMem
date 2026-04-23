## HiveMem 6.4.0

This release makes the HiveMem GUI shippable: the Vue knowledge UI is now
bundled into the server image, the login/session flow works on plain HTTP
LAN deployments, and the canvas is driven by a dedicated REST endpoint that
keeps the MCP tool catalogue agent-focused.

### Highlights

- **Knowledge UI is bundled into the Spring Boot image.**
  A `node:22-alpine` Dockerfile stage builds `knowledge-ui/dist` and stages it
  into `src/main/resources/static` before the Maven build. Previously the image
  shipped without any UI assets, so `/` returned a Spring whitelabel 404.
- **Session-cookie auth works on HTTP LAN deployments.**
  - `server.servlet.session.tracking-modes: cookie` disables Tomcat's URL
    rewriting so browsers never get redirected to `/;s=SESSIONID`, which had
    no routing mapping and produced a 404.
  - `SESSION_COOKIE_SECURE` now defaults to `false`; set it to `true` behind
    an HTTPS reverse proxy.
  - `SpaController` maps `/` alongside deep links so the SPA shell loads at
    the root URL.
- **REST endpoint for the GUI canvas snapshot.**
  - `GET /api/gui/stream` returns `{cells, tunnels, done}` for the canvas to
    render the force-graph without polluting the MCP tool list. Auth uses the
    same session cookie; unauthenticated XHR callers now receive 401 instead
    of a login-page redirect.
  - `CellReadRepository.streamSnapshot` supplies newest-first cells + tunnels
    with a `title` derived from the first line of `summary`/`content`.
- **`hivemem_wake_up` now exposes the caller's identity and role.**
  The handler still returns the existing `l0_identity` / `l1_critical` context
  rows, but now nested under `context`; the top-level payload adds
  `{identity, role}` so clients (agent or GUI) can gate on the principal
  without a separate call.
- **Canvas uses the universal `hivemem_list` tool.**
  `loadTopLevel` drops the (never-implemented) `hivemem_list_realms` call and
  uses `hivemem_list` with no arguments, adapting the `{value,label,cell_count}`
  shape to the existing `Realm` type. The long-poll streaming tool is replaced
  by the dedicated REST endpoint above.

### GUI fixes

- `HttpApiClient.call` now unwraps the MCP envelope
  (`result.content[0].text`) and parses it as JSON before returning, so Vue
  stores see tool output instead of the raw JSON-RPC frame.
- `colorForRealm` tolerates `null`/`undefined` realms (three committed cells
  in the reference dataset have no realm) — the force-graph route no longer
  crashes with `Cannot read properties of null (reading 'length')`.
- `SphereCanvas` derives the signal set from the loaded cell stream when realm
  metadata is flat, so cells and tunnels actually render in the default home
  view instead of staying invisible behind the realm spheres.
- Build repairs in the UI: parameter-property syntax under `erasableSyntaxOnly`,
  template-scope `location.reload()`, and explicit `.mjs` extension for
  `pdfjs-dist/web/pdf_viewer`.

### Operational notes

- **Breaking for HTTP deployments only:** if you were relying on the old
  default `SESSION_COOKIE_SECURE=true`, set it explicitly in your environment.
- **LXC/Proxmox hosts:** the container still needs
  `--security-opt apparmor=unconfined` to open sockets; this is unchanged
  from prior releases.
- No database migrations.
- No MCP tool removals or renames; `hivemem_wake_up` adds fields additively.

### Upgrade

```bash
docker pull ghcr.io/ufelmann/hivemem:6.4.0
docker stop hivemem && docker rm hivemem
docker run -d --name hivemem \
  --network hivemem-net -p 8421:8421 \
  --security-opt apparmor=unconfined \
  -e HIVEMEM_JDBC_URL=... -e HIVEMEM_DB_USER=... -e HIVEMEM_DB_PASSWORD=... \
  -e HIVEMEM_EMBEDDING_URL=http://hivemem-embeddings:80 \
  --restart unless-stopped \
  ghcr.io/ufelmann/hivemem:6.4.0
```
