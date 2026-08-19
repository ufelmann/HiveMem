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
import { readFileSync } from 'node:fs'

const sw = readFileSync(new URL('../dist/sw.js', import.meta.url), 'utf8')
const problems = []

const requiredOriginPaths = [
  '\\/login', '\\/logout', '\\/oauth\\/', '\\/admin', '\\/api\\/',
  '\\/mcp', '\\/hooks', '\\/sync', '\\/vistierie', '\\/\\.well-known\\/',
]
for (const src of requiredOriginPaths) {
  if (!sw.includes(src)) {
    problems.push(`sw.js shell route is missing ${src} — that route would be hijacked by the SPA shell`)
  }
}

// The shell navigation route itself must be present with a bounded timeout.
if (!sw.includes('NetworkFirst') || !sw.includes('hivemem-shell')) {
  problems.push('sw.js has no NetworkFirst "hivemem-shell" navigation route — a navigation would not reach the network')
}
if (!sw.includes('networkTimeoutSeconds:3')) {
  problems.push('sw.js NetworkFirst shell route is missing networkTimeoutSeconds:3')
}

// index.html must never be precached — that is the root cause this whole change fixes.
if (/url:"index\.html"/.test(sw)) {
  problems.push('sw.js still precaches index.html — Access can never challenge a navigation')
}

// No OTHER runtime caching strategy is allowed: an /api runtime handler would break granular
// XHR upload progress. NetworkFirst is expected (the shell route); anything beyond that is not.
const otherStrategies = ['NetworkOnly', 'StaleWhileRevalidate', 'CacheFirst', 'CacheOnly']
for (const strategy of otherStrategies) {
  if (new RegExp(`\\b${strategy}\\b`).test(sw)) {
    problems.push(`sw.js contains an unexpected "${strategy}" runtime caching strategy`)
  }
}

if (problems.length) { console.error('check-sw FAILED:\n- ' + problems.join('\n- ')); process.exit(1) }
console.log('check-sw OK')
