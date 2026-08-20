// Build-regression guard: fail if the compiled service worker would hijack server-rendered
// routes (login/oauth/etc.), fail to reach the network for a navigation, or intercept /api.
// Run after `npm run build`.
//
// The shell is no longer excluded via navigateFallbackDenylist (that mechanism relied on a
// precached index.html, which is exactly what broke Cloudflare Access recovery — see
// vite.config.ts). Instead a NetworkFirst runtimeCaching route only matches navigations
// whose pathname is not one of these origin-owned prefixes. Assert both halves: the shell
// route exists, and the exclusion list survived workbox-build's `.toString()` serialization
// of the urlPattern function (a closure over the array would NOT survive it — verified: an
// earlier version of this file referenced a module-scope const and the pattern list came out
// missing from dist/sw.js entirely).
import { readFileSync, readdirSync } from 'node:fs'

const distDir = new URL('../dist/', import.meta.url)
const sw = readFileSync(new URL('sw.js', distDir), 'utf8')
const problems = []

// The worker must be installable as a single file: Cloudflare Access sits in front of
// every other asset, so a sessionless browser can fetch sw.js itself (explicitly
// bypassed at the edge) but any importScripts() sibling it pulls in gets redirected to
// the Access login page instead — the install fails and a stuck client can never
// self-heal. inlineWorkboxRuntime: true in vite.config.ts is what prevents this; assert
// both symptoms it would leave behind.
if (sw.includes('importScripts(')) {
  problems.push('sw.js still calls importScripts() — a sessionless browser cannot install the worker, so a stuck client can never heal')
}
const workboxSiblings = readdirSync(distDir).filter((f) => /^workbox-.*\.js$/.test(f))
if (workboxSiblings.length) {
  problems.push(`dist/ still contains a separate workbox chunk (${workboxSiblings.join(', ')}) — a sessionless browser cannot install the worker, so a stuck client can never heal`)
}

const requiredOriginPaths = [
  '\\/login', '\\/logout', '\\/oauth\\/', '\\/admin', '\\/api\\/',
  '\\/mcp', '\\/hooks', '\\/sync', '\\/vistierie', '\\/\\.well-known\\/',
  '\\/cdn-cgi\\/',
]
for (const src of requiredOriginPaths) {
  if (!sw.includes(src)) {
    problems.push(`sw.js shell route is missing ${src} — that route would be hijacked by the SPA shell`)
  }
}

// The shell navigation route itself must be present with a bounded timeout.
//
// With inlineWorkboxRuntime (see vite.config.ts), workbox-build's production bundling
// step runs terser with `mangle: { toplevel: true }` over the whole file, which renames
// bare top-level identifiers — including imported strategy classes like `NetworkFirst`.
// The literal string "NetworkFirst" therefore does NOT survive in dist/sw.js any more
// (verified against the actual build output). terser's property mangling is scoped to
// `/(^_|_$)/` though, so plain object-literal keys we pass in our own config — like
// `networkTimeoutSeconds` and `cacheName` — are left untouched and remain reliable
// fingerprints of "a NetworkFirst route with our shell config exists".
if (!sw.includes('hivemem-shell')) {
  problems.push('sw.js has no "hivemem-shell" navigation route — a navigation would not reach the network')
}
if (!/networkTimeoutSeconds\s*:\s*3/.test(sw)) {
  problems.push('sw.js NetworkFirst shell route is missing networkTimeoutSeconds:3')
}

// The precache manifest built from globPatterns must be completely empty, not merely
// missing index.html: every precache entry is fetched during the service worker's
// `install` event, and Cloudflare Access sits in front of ALL built assets (verified live:
// /assets/index-*.js and /favicon.svg both 302 to the Access login page for a sessionless
// request). A single non-OK response fails the whole install ('bad-precaching-response').
// A sessionless browser is exactly the client that needs the new worker installed in order
// to stop hijacking the Access login callback — so any non-empty manifest leaves that
// browser permanently unable to install the worker, and a stuck client can never heal.
//
// KNOWN, MEASURED EXCEPTION: vite-plugin-pwa@1.3.0 unconditionally appends one
// `manifest.webmanifest` entry via `workbox.additionalManifestEntries` whenever the
// `manifest` option is set (src: configureStaticAssets in vite-plugin-pwa/dist/index.js) —
// this happens independently of globPatterns and there is no plugin option to suppress it
// short of disabling the web manifest (and PWA installability) entirely, which is out of
// scope for this fix. Verified: `globPatterns: []` still yields exactly one
// `{url:"manifest.webmanifest",...}` entry in dist/sw.js. That single small JSON file is
// NOT yet confirmed to be Cloudflare-Access-exempt (unlike the JS/CSS/PNG/SVG bundle,
// which was the measured 99-entry blocker this fix removes) — this codebase already
// exempts /manifest.webmanifest at the *application* auth layer (see
// docs/superpowers/specs/2026-07-16-pwa-upload-spec-a-design.md, Fund C2/F1), so an
// equivalent edge-level Cloudflare Access bypass for this one path is the natural
// follow-up, not something fixable from this repo's build config.
const precacheEntries = sw.match(/\{url:"/g) ?? []
const allowedPrecacheEntries = ['{url:"manifest.webmanifest"']
const unexpectedEntries = (sw.match(/\{url:"[^"]*"/g) ?? []).filter(
  (entry) => !allowedPrecacheEntries.includes(entry),
)
if (unexpectedEntries.length) {
  problems.push(`sw.js precache manifest contains unexpected entries (${unexpectedEntries.join(', ')}) — every entry sits behind Cloudflare Access, so a sessionless browser's install fails and a stuck client can never heal; see globPatterns: [] in vite.config.ts`)
}
if (precacheEntries.length > allowedPrecacheEntries.length) {
  problems.push(`sw.js precache manifest has ${precacheEntries.length} entries, more than the ${allowedPrecacheEntries.length} known/accepted one(s) (manifest.webmanifest) — investigate before shipping`)
}

// No OTHER runtime caching route is allowed: an /api runtime handler would break granular
// XHR upload progress. Exactly one route (the shell) is expected. Strategy class names are
// unrecoverable post-mangle (see comment above), so count the one thing every route we
// write always carries: a quoted, literal `cacheName:"..."` from our own config object —
// each distinct route we author gets its own cache name string in the built output.
const cacheNameLiterals = sw.match(/cacheName:"[^"]*"/g) ?? []
if (cacheNameLiterals.length !== 1) {
  problems.push(`sw.js must contain exactly one runtime-caching route with a literal cacheName (the shell), found ${cacheNameLiterals.length} (${cacheNameLiterals.join(', ') || 'none'}) — a second runtime-caching route would break /api upload progress`)
}
if (!sw.includes('"navigate"===')) {
  problems.push('sw.js shell route predicate is not navigation-gated (expected "navigate"=== in the serialized urlPattern) — it may match non-navigation requests too')
}

// With navigateFallback disabled and no precached index.html, a route never visited online
// (e.g. /photos, or any URL with a query string) has no cache entry of its own and fails
// offline unless the shell route falls back to whatever WAS cached at '/'. Assert the
// handlerDidError plugin survived serialization self-contained: workbox-build's `.toString()`
// does NOT capture closures (see vite.config.ts and the urlPattern check above), so a
// module-scope reference here would compile to a dead `caches` identifier instead of the
// global. globalThis.caches is the only form that has been verified to survive.
if (!sw.includes('handlerDidError') || !sw.includes('globalThis.caches.match("/")')) {
  problems.push('sw.js shell route is missing a self-contained handlerDidError fallback to globalThis.caches.match("/") — an unvisited route would fail offline instead of falling back to the cached shell')
}

if (problems.length) { console.error('check-sw FAILED:\n- ' + problems.join('\n- ')); process.exit(1) }
console.log('check-sw OK')
