import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import vuetify from 'vite-plugin-vuetify'
import { VitePWA } from 'vite-plugin-pwa'

// Exported for the same reason as workboxOptions below: vite-plugin-pwa@1.3.0's plugin
// instance does not expose `api.options`, so tests/unit/pwaReload.spec.ts asserts on this
// named export instead. autoUpdate, not 'prompt': a worker that only updates when a toast
// is clicked can serve a stale shell indefinitely — and would outlive the shell fix below.
export const registerType = 'autoUpdate' as const

// Exported so tests/unit/pwaConfig.spec.ts can assert on the resolved workbox options
// directly: vite-plugin-pwa@1.3.0's plugin instance does not expose `api.options`.
export const workboxOptions = {
  globPatterns: ['**/*.{js,css,svg,png,woff2}'],
  // Deliberately no 'html': a precached index.html means opening '/' issues no network
  // request at all, since the service worker resolves it straight from the precache. With
  // no request ever leaving the browser, Cloudflare Access never gets a navigation to
  // challenge, and an expired session dead-ends on the app's client-side error screen
  // instead of being redirected to the identity provider. See the spec for the capture.
  // Overrides vite-plugin-pwa's "index.html" default (dist/index.js:838); Object.assign
  // copies undefined values, so this really does disable the precache-bound route.
  navigateFallback: undefined,
  runtimeCaching: [
    {
      // workbox-build's generateSW serializes this function via `.toString()` and inlines
      // it verbatim into dist/sw.js — it does NOT capture the closure over ORIGIN_PATHS.
      // Referencing the module-scope const here would compile to a `ReferenceError` at
      // runtime in the browser (verified against the actual dist/sw.js output). The pattern
      // list is therefore duplicated as a literal *inside* the function so serialization
      // carries it along self-contained.
      urlPattern: ({ request, url }: { request: Request; url: URL }) => {
        // Kept in sync by hand with scripts/check-sw.mjs's `requiredOriginPaths`: that
        // script re-derives this exact list from the built dist/sw.js to catch drift, since
        // serialization (see comment above) rules out sharing one constant across the
        // config/worker boundary. Edit both when this list changes.
        const originPaths = [
          /^\/login/, /^\/logout/, /^\/oauth\//, /^\/admin/, /^\/api\//,
          /^\/mcp/, /^\/hooks/, /^\/sync/, /^\/vistierie/, /^\/\.well-known\//,
        ]
        return request.mode === 'navigate' && !originPaths.some((re) => re.test(url.pathname))
      },
      handler: 'NetworkFirst' as const,
      options: {
        cacheName: 'hivemem-shell',
        // Bounded so an offline PWA still starts from the runtime cache instead of
        // hanging; the cost is a up-to-3s wait on a genuinely offline cold start.
        networkTimeoutSeconds: 3,
        cacheableResponse: { statuses: [200] },
      },
    },
  ],
}

export default defineConfig(async ({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  const proxyTarget = env.VITE_PROXY_TARGET ?? 'http://localhost:8421'
  const token = env.VITE_HIVEMEM_TOKEN

  // Dev convenience: the SPA is dual-auth — /mcp uses the bearer token, but /api
  // (graph/hive stream, scans content, thumbnails) uses the session cookie `s`.
  // When developing against a REMOTE backend with a token, log in once here to
  // obtain that cookie and inject it into every proxied request, so those views
  // work without a manual visit to /login. No-op for a local target or no token.
  //
  // Gated on command === 'serve': this must never fire outside `npm run dev`. Both
  // `vite build` and, critically, importing this config from a test runner (see
  // tests/unit/pwaConfig.spec.ts, which invokes configFactory with command: 'build')
  // resolve the async config function — without this guard that import alone would
  // POST a real credential from VITE_HIVEMEM_TOKEN to a remote host on every
  // `npm run test:unit`.
  let sessionCookie: string | null = null
  if (
    command === 'serve' &&
    token &&
    !proxyTarget.includes('localhost') &&
    !proxyTarget.includes('127.0.0.1')
  ) {
    try {
      const res = await fetch(`${proxyTarget}/login`, {
        method: 'POST',
        headers: { 'content-type': 'application/x-www-form-urlencoded' },
        body: `v=${encodeURIComponent(token)}`,
        redirect: 'manual',
      })
      const cookies = res.headers.getSetCookie?.() ?? [res.headers.get('set-cookie') ?? '']
      const s = cookies.map((c) => c.match(/(^|\s)(s=[^;]+)/)?.[2]).find(Boolean)
      if (s) {
        sessionCookie = s
        console.log(`[dev-proxy] logged in at ${proxyTarget}; session cookie injected into /api`)
      } else {
        console.warn('[dev-proxy] /login returned no session cookie — is the token valid?')
      }
    } catch (e) {
      console.warn(`[dev-proxy] auto-login failed (${(e as Error).message}); /api will 401 until you log in manually`)
    }
  }

  return {
    plugins: [
      vue({
        template: {
          compilerOptions: {
            isCustomElement: (tag) =>
              (tag.startsWith('Tres') && tag !== 'TresCanvas') || tag === 'primitive',
          },
        },
      }),
      vuetify({ autoImport: true }),
      VitePWA({
        registerType,
        // Generates all icons from the existing brand SVG at build time and injects the
        // manifest icon entries + apple-touch-icon <link> into index.html.
        pwaAssets: { preset: 'minimal-2023', image: 'public/favicon.svg' },
        manifest: {
          name: 'HiveMem',
          short_name: 'HiveMem',
          description: 'Local-first knowledge graph — your second brain',
          theme_color: '#0a0a1a',
          background_color: '#0a0a1a',
          display: 'standalone',
          orientation: 'any',
          start_url: '/',
        },
        workbox: {
          ...workboxOptions,
          // Intentionally the only runtime route is the shell navigation route above;
          // /api and everything else pass through the SW untouched (network-only,
          // uncached), which keeps granular XHR upload progress.
        },
      }),
    ],
    server: {
      host: '0.0.0.0',
      port: 5173,
      // Dev only: proxy same-origin so the session cookie works. /login+/logout are
      // required to actually obtain the cookie on localhost:5173.
      // Override the target via VITE_PROXY_TARGET (e.g. in an untracked .env.local)
      // to debug the UI against a remote backend.
      proxy: Object.fromEntries(
        ['/api', '/mcp', '/login', '/logout'].map((path) => [
          path,
          {
            target: proxyTarget,
            // Keep the Host header: the backend builds its absolute /login redirect from
            // it, so changeOrigin would bounce the browser to the backend origin and out
            // of the dev server.
            changeOrigin: false,
            // Chrome sends Origin on every POST, even same-origin. A remote backend's
            // CORS allowlist does not contain this dev origin and answers 403 "Invalid
            // CORS request"; dropping the header makes the request non-CORS, which the
            // backend accepts. No-op when the target is localhost.
            // Also inject the session cookie obtained above, but only when the browser
            // did not send its own (a real /login in the browser wins).
            configure: (proxy: any) => {
              proxy.on('proxyReq', (proxyReq: any) => {
                proxyReq.removeHeader('origin')
                if (sessionCookie && !proxyReq.getHeader('cookie')) {
                  proxyReq.setHeader('cookie', sessionCookie)
                }
              })
            },
          },
        ]),
      ),
    },
  }
})
