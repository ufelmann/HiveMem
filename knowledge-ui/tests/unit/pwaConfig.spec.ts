import { describe, expect, it } from 'vitest'
import configFactory, { workboxOptions } from '../../vite.config'

/**
 * The generated service worker is what actually regressed in production: a precached
 * index.html means opening '/' issues no network request, so Cloudflare Access can never
 * challenge a navigation and an expired session cannot recover. Asserting on the config
 * object catches that; asserting on a built dist/sw.js would make this suite depend on a
 * prior build.
 *
 * vite-plugin-pwa@1.3.0's plugin instance does not expose `api.options` (verified: the
 * plugin's `api` only carries disabled/pwaInDevEnvironment/webManifestData/registerSWData/
 * generateBundle/generateSW/extendManifestEntries/pwaAssetsGenerator), so per the plan's
 * fallback the workbox options are read from the named export `workboxOptions` in
 * vite.config.ts instead. The plugin-registration assertion below is kept regardless.
 */
describe('PWA workbox config', () => {
  it('registers the vite-plugin-pwa plugin', async () => {
    const config = await (configFactory as any)({ command: 'build', mode: 'production' })
    const plugins = config.plugins.flat(Infinity)
    const pwa = plugins.find((p: any) => p?.name?.includes('vite-plugin-pwa'))
    expect(pwa, 'vite-plugin-pwa must be registered').toBeTruthy()
  })

  it('inlines the workbox runtime so sw.js is installable as a single file', () => {
    // Cloudflare Access sits in front of every asset except sw.js itself, so a second,
    // content-hashed workbox-*.js chunk pulled in via importScripts() would be
    // unreachable to a sessionless browser. See vite.config.ts for the full rationale.
    expect(workboxOptions.inlineWorkboxRuntime).toBe(true)
  })

  it('precaches nothing — every built asset sits behind Cloudflare Access, so a non-empty install-time manifest would make sw.js un-installable for a sessionless browser', () => {
    expect(workboxOptions.globPatterns).toEqual([])
  })

  it('handles navigations NetworkFirst with a bounded timeout', () => {
    expect(workboxOptions.navigateFallback).toBeUndefined()
    const nav = workboxOptions.runtimeCaching.find((r) => r.handler === 'NetworkFirst')
    expect(nav, 'a NetworkFirst navigation route must exist').toBeTruthy()
    expect(nav!.options.networkTimeoutSeconds).toBe(3)
  })

  it('keeps the origin-served paths out of shell handling', () => {
    const nav = workboxOptions.runtimeCaching.find((r) => r.handler === 'NetworkFirst')!
    const navigate = (pathname: string) =>
      nav.urlPattern({ request: { mode: 'navigate' } as Request, url: new URL(`https://example.com${pathname}`) })
    expect(navigate('/')).toBe(true)
    expect(navigate('/photos')).toBe(true)
    for (const p of ['/login', '/logout', '/oauth/x', '/admin', '/api/config', '/mcp', '/hooks', '/sync', '/vistierie', '/.well-known/x', '/cdn-cgi/access/authorized', '/cdn-cgi/access/get-identity']) {
      expect(navigate(p), `${p} must not be handled by the shell route`).toBe(false)
    }
  })

  it('never handles a non-navigation request', () => {
    const nav = workboxOptions.runtimeCaching.find((r) => r.handler === 'NetworkFirst')!
    expect(nav.urlPattern({ request: { mode: 'cors' } as Request, url: new URL('https://example.com/api/tools/call') })).toBe(false)
  })

  it('falls back to the cached shell when an unvisited route fails offline', async () => {
    // Without navigateFallback and with no precached index.html, a route never visited
    // online (e.g. /photos) has no cache entry of its own. The handlerDidError plugin must
    // fall back to whatever shell response was cached at '/'. This must reference only
    // web-platform globals, never a closed-over module variable — workbox-build's
    // generateSW serializes it via `.toString()` and a closure would compile to a dead
    // identifier in dist/sw.js (see scripts/check-sw.mjs and vite.config.ts).
    const nav = workboxOptions.runtimeCaching.find((r) => r.handler === 'NetworkFirst')!
    const fallbackPlugin = nav.options.plugins?.find((p: any) => typeof p.handlerDidError === 'function')
    expect(fallbackPlugin, 'a handlerDidError plugin must exist on the shell route').toBeTruthy()

    const shellResponse = new Response('cached shell')
    const originalCaches = (globalThis as any).caches
    ;(globalThis as any).caches = { match: async (key: string) => (key === '/' ? shellResponse : undefined) }
    try {
      await expect(fallbackPlugin!.handlerDidError()).resolves.toBe(shellResponse)
    } finally {
      ;(globalThis as any).caches = originalCaches
    }
  })
})
