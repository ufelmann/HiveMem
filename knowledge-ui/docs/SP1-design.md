# SP1 — Knowledge-UI Design

**Date:** 2026-04-19
**Branch:** `feat/knowledge-ui`
**GitHub issue:** #2 (scope-updated via comment)
**Related HiveMem drawers:** `ff28f80b` (Vision), `b2c9e9a3` (UI concept), `6eb85cf6` (3D-Pivot)

---

## 1. Purpose

A 2D sphere-canvas frontend for HiveMem. Replaces Obsidian (notes) and Paperless (document archive) as a unified human+AI knowledge interface. First of five sub-projects; standalone, consumes only existing MCP read-tools, no backend changes required.

## 2. User-facing concept

The user opens the app and sees **Wing-Halos with Drawer-Points** inside each halo — all clusters visible at once, coloured by wing. Spheres have sizes proportional to a user-selectable knowledge metric, connected by edges whose thickness reflects tunnel count. Mouse-wheel zooms smoothly; a click triggers a cinematic snap-focus animation into the chosen sphere. The hierarchy (Wing → Hall → Room → Drawer) is traversed by repeated click+snap; empty levels (single-child Halls/Rooms) are transparently skipped.

Clicking a leaf Drawer opens a right-side **Scan panel** with L1 Summary, L2 Key Points, L3 Insight, tunnels and attachments. Double-click switches into **Reader mode**: a fullscreen overlay with a wide serif reading column, tabs for Markdown (rendered via markdown-it) and each attachment (PDF via pdf.js, .eml via postal-mime). Editing is out of scope (comes in SP4).

The app shell is a 56px left icon-rail + the full-bleed canvas. Icons open slide-out panels (search, wings tree, reading list, cinema route, stats, history). `Cmd/Ctrl+K` opens a fuzzy-search palette anywhere.

## 3. Locked design decisions

| Aspect | Decision |
|---|---|
| Top-level view | Wing-halos with drawer-points inside |
| Zoom | Smooth continuous zoom + snap-focus on click |
| Hierarchy | Adaptive — single-child levels are transparently skipped |
| Sphere size | Hybrid metric with slider; default = drawer count; alts = importance-weighted / content volume (char count) / popularity |
| Edge thickness | `log(1 + tunnel_count)`, always visible, opacity fades with zoom-out |
| Edge colour | Fixed per relation-type: `related_to` grey, `builds_on` cyan, `contradicts` red, `refines` green |
| Shell | 56px icon-rail + canvas; slide-out panels on icon click; `Cmd+K` for search |
| Drawer interaction | Scan mode (single-click slide-panel) + Reader mode (double-click fullscreen) |
| Attachments | External URL/file-path references only (SP2 adds upload + storage) |
| Rendering | **PixiJS** (WebGL 2D engine) — not SVG, not TresJS |
| Cinema mode | Lazy-loaded route `/cinema` with recycled TresJS HiveSphere — isolated from 2D canvas |
| Auth | Bearer token entered in a first-run dialog; stored in localStorage |
| Mobile | Desktop-first; responsive fallback (bottom rail, fullscreen overlays) |
| Theme | Dark-only for v1 |

## 4. Architecture

```
Browser (Vue 3 SPA)
  ├── Canvas-Layer    (PixiJS Application, ~1 Vue wrapper component)
  ├── Shell-Layer     (Vuetify 3: icon-rail, slide-panels, dialogs, chips)
  ├── Reader-Layer    (fullscreen v-dialog with pdf.js, postal-mime, markdown-it)
  ├── Cinema route    (lazy-loaded, TresJS + recycled HiveSphere)
  └── State           (Pinia: 5 modules)

       ↓ HTTP JSON-RPC (Bearer token)

HiveMem Java-Server (existing, unchanged for SP1 v1)
```

### Tech stack

- **Vue 3 + Vite + TypeScript** (scaffold already in `knowledge-ui/`, commit `02715e6`)
- **Vuetify 3** — app shell, panels, dialogs, form controls
- **Pinia** — reactive state (5 modules)
- **PixiJS 8 + pixi-filters** — 2D canvas rendering, GPU-accelerated
- **d3-force** — initial layout of wing positions
- **markdown-it + KaTeX** — L0 rendering
- **pdf.js** (dynamic import) — PDF attachments
- **postal-mime** — .eml parsing
- **@vueuse/core** — keybindings, debouncing, window-size
- **TresJS** — only inside `/cinema` route, lazy-loaded

## 5. Pinia store modules

- **`auth`** — token, role, login dialog. `login(token)` calls `hivemem_wake_up`, persists role to localStorage.
- **`canvas`** — wings + drawers + tunnels as graph data. `loadTopLevel()`, `loadWing(id)`. Aggregate metrics (drawer count per wing, tunnel density) computed once and cached.
- **`drawer`** — focused drawer with L0-L3, facts, incoming + outgoing tunnels. Lazy-loaded via `hivemem_get_drawer`. LRU-evicted at 50 entries.
- **`reader`** — reader-mode state: active tab, PDF page/zoom, edit mode (SP4-reserved).
- **`ui`** — panel visibility, search query, sphere-size metric, theme.

## 6. API layer and mock

Interface-first:

```ts
interface ApiClient {
  call<T>(tool: string, args: Record<string, unknown>): Promise<T>
  subscribe(onEvent: (e: HiveEvent) => void): () => void  // returns unsubscribe
}

type HiveEvent =
  | { type: 'drawer_added';  drawer: Drawer }
  | { type: 'drawer_revised'; id: string; parent_id: string }
  | { type: 'tunnel_added';   from: string; to: string; relation: string }
  | { type: 'status';         last_activity: string }
```

Two implementations:

- **`HttpApiClient`** — real HiveMem JSON-RPC over `POST /mcp` with `Authorization: Bearer <token>`. `subscribe()` polls `hivemem_status` every 10s, diffs `last_activity`, emits `status` event when it changes; SP1 v1 strategy.
- **`MockApiClient`** — loads `mock.ts` (47 drawers + 46 facts snapshot). Implements all 15 read tools deterministically with 50-200ms random latency. `subscribe()` emits a fake `drawer_added` every 15s and `tunnel_added` every 30s to exercise live-update UI.

Toggle: `VITE_USE_MOCK=true` at build time, or `localStorage.setItem('hivemem_mock', 'true')` at runtime. DevTools convenience: `window.__useMock(bool)`.

SP1 v2 (separate PR to `main`, not blocking): SSE endpoint `GET /mcp/events?token=…` in the Java server using `SseEmitter`, bridged from `pg_notify`. `HttpApiClient.subscribe()` becomes `new EventSource(...)`.

## 7. Canvas rendering (PixiJS)

### Scene graph

```
stage (PIXI.Application root)
 └─ worldContainer        (zoom + pan transforms applied here)
     ├─ edgesLayer        (Graphics, under spheres)
     ├─ haloLayer         (Sprites with radial gradient per wing)
     ├─ spheresLayer      (Wing + Drawer sprites)
     └─ labelsLayer       (BitmapText, zoom-gated)
 └─ overlayLayer          (HUD, breadcrumb — not zoomed)
```

### Sphere visuals

Each wing has a **proceduraly-generated sprite texture** (256×256 offscreen canvas, then `PIXI.Texture.from(canvas)`): 3-stop radial gradient (glow/base/tint from `wingPalette.ts`, already in scaffold) + Perlin noise + slight displacement. Textures are cached per wing — one generation per wing, reused everywhere.

Drawer points use a shared smaller texture tinted via `ColorMatrixFilter` to the wing colour.

### Filter stack

- **Focused sphere**: `AdvancedBloomFilter({ threshold: 0.2, bloomScale: 1.5 })` + `OutlineFilter({ color: 0x4dc4ff, thickness: 2 })`
- **Hovered sphere**: lighter bloom (bloomScale 0.8)
- **Snap-focus transition** (200ms): brief `ZoomBlurFilter` pulse on worldContainer
- **Background**: faint `GodrayFilter` streaks, very low intensity

### Particles

Persistent emitter: 200-400 slowly-drifting gold/cyan dust motes in worldContainer. Recycled from `CyberBees` logic. Count halved on mobile.

### Layout algorithm

- **Wing positions**: `d3-force` simulation with wing-to-wing tunnel counts as link strength. Runs once on load, settles within ~200 ticks, then frozen. Final positions written to localStorage per user; user can drag a wing and override. Drag triggers a 60-tick re-settle of that wing's neighbours, then freeze.
- **Drawer positions within a wing halo**: deterministic Poisson-disk samples inside the halo radius, seeded by drawer id. Gives even, non-overlapping distribution. New drawers interpolate in (fade + grow over 400ms).

### Level of Detail (zoom-gated)

| Zoom range | What renders |
|---|---|
| < 0.3× | Wings only, flat circles, no labels |
| 0.3–1.0× | Wing halos + drawer points, wing labels only |
| 1.0–3.0× | Drawer labels on hover, edges full colour |
| > 3.0× | All labels, edge relation colours, click-ready hit boxes expand |

### Culling

Custom viewport-culling in the per-frame update: only sphere sprites whose world-AABB intersects the viewport get visible=true. Ensures smoothness at 10k+ drawers even though SP1 will see ≤ 500 in practice.

## 8. Shell layout

### Icon rail (56 px wide, always visible)

| Icon | Opens |
|---|---|
| Logo `H` | (decoration) |
| 🔍 Search | Search panel — fuzzy + ranked, 5-signal sliders |
| 🌟 Wings | Wings tree + tag/importance filters |
| 📚 Reading | Reading list (references with `status=unread`) |
| 🌌 Cinema | Navigates to `/cinema` route |
| 📊 Stats | Panel with counts + pending-approvals (admin only) |
| ⏳ History | Recent access log / own edits |
| ⚙ Settings | Theme, mock-mode toggle, logout |

Panels slide out at 240-320 px; `Esc` closes; click outside closes; pin-icon keeps open.

### Keyboard shortcuts (via `@vueuse/core`)

- `Cmd/Ctrl + K` — open search palette anywhere
- `Esc` — close panel / exit reader / back one zoom step
- `Enter` — open reader for focused drawer
- `Space` — pan mode (hold)
- `+` / `-` — zoom in/out
- `0` — reset zoom to fit
- `?` — keyboard shortcut help dialog

## 9. Drawer detail

### Scan mode (single click on drawer sphere)

Right slide-panel ~360 px wide, non-blocking. Sections:
1. Title + wing/hall/room chips + importance + timestamps
2. L1 Summary
3. L2 Key Points (bullet list)
4. L3 Insight (quote-block style)
5. Tunnels — grouped by relation, click jumps canvas focus
6. Attachments — listed with type icon, click opens reader on that tab
7. Facts — horizontal mini-timeline of `quick_facts` with `valid_from`/`valid_until` span
8. "Open reader" button

### Reader mode (double click, `Enter`, or the button)

Fullscreen `<v-dialog fullscreen>`. Structure:

- **Top bar**: back arrow, tabs (Markdown / each attachment), tools (edit-toggle in SP4, copy link, meta-panel-toggle)
- **Main column**: centered, max-width 720 px, serif (Georgia / Charter), 16-18px line-height 1.6
- **Right meta-panel** (collapsible): wing/hall/room, importance, timestamps, tunnels shortcut, facts

Renderers:

- **Markdown (L0)**: `markdown-it` + KaTeX + highlight.js; images/links allowed
- **PDF attachment**: `pdf.js` PDFViewer with scroll, zoom, text selection, search
- **.eml attachment**: `postal-mime` parses → render headers, body (HTML or plain-text), quoted-replies collapsible, attachment list

In SP1 v1, the edit button is disabled with a tooltip pointing to SP4.

## 10. Role-based visibility

Visibility is derived from the role returned by `hivemem_wake_up`:

| Role | Icon rail | Panels | Actions |
|---|---|---|---|
| admin | all 8 icons | full; pending-approvals visible in Stats | (reader-only in SP1) |
| writer | 7 icons (no admin bits) | all except pending-approvals | (reader-only in SP1) |
| reader | 7 icons | all except pending-approvals | reader-only |
| agent | 7 icons | same as writer | reader-only (writes happen via agent process, not UI) |

The UI never assumes; it hides what the backend would deny anyway. Defense-in-depth.

## 11. Open items deferred to SP1-v2 / SP2+

- SSE endpoint + `HttpApiClient.subscribe()` upgrade from polling → streaming
- Light theme
- Attachment upload (comes with SP2)
- Inline editor (comes with SP4)
- Obsidian vault import (comes with SP4)
- OCR ingestion (comes with SP3)
- Directory watcher (comes with SP5)

## 12. Acceptance criteria

- App loads in under 2 seconds against HiveMem production with 47 drawers
- Canvas sustains 60 fps with up to 500 visible nodes and 400 particles
- Mock mode operates fully offline; all read tools return deterministic data with simulated latency
- All four roles are correctly filtered in icon rail and panels
- Scan mode shows L1 / L2 / L3 / tunnels / attachments correctly for any drawer
- Reader mode successfully renders: Markdown (via markdown-it), a test PDF (via pdf.js), a test .eml file (via postal-mime)
- Polling detects new drawers and emits a toast with a reload link within 15 seconds of a change
- `MockApiClient.subscribe()` emits test events every 15 / 30 seconds
- Keyboard shortcuts all function
- `vue-tsc --noEmit` passes with zero errors
- `vite build` succeeds in under 3 seconds
- Playwright E2E smoke test passes: Login → Search → Open Drawer → Enter Reader → Close Reader → Logout
- Cinema route navigates to `/cinema`, lazy-loads TresJS + HiveSphere, renders at 60 fps

## 13. Implementation sketch (order of work)

1. **Foundation**: port reusable scaffold files (already done in commit `02715e6`); delete dead code; add PixiJS + pixi-filters + d3-force + markdown-it + KaTeX + postal-mime + pdf.js to `package.json`
2. **API layer**: `ApiClient` interface, `HttpApiClient` (JSON-RPC over fetch), `MockApiClient` wrapping `mock.ts`; `useApi()` composable picks impl based on flag
3. **Auth**: Pinia `auth` store + first-run dialog + localStorage + logout
4. **Shell**: Vuetify app-bar-less layout, icon rail + slide-panel scaffold, keyboard shortcuts
5. **Canvas v1**: PixiJS application mounted in wrapper component, render hardcoded 2 wings + 5 drawers to prove the pipeline, add bloom + outline filters
6. **Data integration**: connect `canvas` store to `ApiClient`, render real wings + drawers from data
7. **Layout**: d3-force initial settle, freeze, Poisson-disk drawer placement, localStorage persistence
8. **Interaction**: zoom (wheel + pinch), pan (drag), hover highlight, click snap-focus, adaptive level-skip
9. **LOD + culling**: zoom-gated visibility + viewport AABB culling
10. **Scan mode**: `drawer` store + right slide-panel + wire tunnels/attachments
11. **Reader mode**: fullscreen dialog + tabs + markdown-it + pdf.js dynamic-import + postal-mime
12. **Role gating**: role-derived visibility in UI
13. **Polling + subscribe**: `status`-diff polling in `HttpApiClient`, toast notification in UI
14. **Cinema route**: lazy-import TresJS + recycled HiveSphere; `/cinema` route
15. **Polish**: particles, godray background, snap-focus `ZoomBlurFilter` pulse
16. **E2E smoke test**: Playwright script, runs in CI as `vite preview` target

## 14. Out of scope (explicitly)

- Writing drawers from the UI (editor = SP4)
- Uploading attachments (storage = SP2)
- OCR / extraction (pipeline = SP3)
- Directory-watch ingestion (= SP5)
- Real-time collaboration / multi-user presence
- End-to-end encryption
- Native mobile apps
- Backward compatibility with the removed 3D prototype

## 15. Risks

- **PixiJS v8 learning curve**: API changed in v8 from v7; docs are good but smaller community than Three.js. Mitigation: contain PixiJS code in one wrapper component with a clear interface.
- **LOD thresholds may need tuning**: real-data visual feedback required. Mitigation: thresholds in a constants file, easy to adjust.
- **pdf.js bundle size**: ~600 KB. Mitigation: dynamic import only when first PDF is opened.
- **SSE not in v1**: polling is adequate for a single-user tool; SSE can ship as a non-blocking SP1-v2 PR.
- **Mock drift**: `mock.ts` snapshot will go stale. Mitigation: regenerate script that calls real HiveMem and dumps updated `mock.ts`.
