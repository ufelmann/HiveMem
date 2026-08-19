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

  it('does not precache html — the shell must reach the network', () => {
    for (const pattern of workboxOptions.globPatterns) {
      expect(pattern).not.toMatch(/\bhtml\b/)
    }
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
    for (const p of ['/login', '/logout', '/oauth/x', '/admin', '/api/config', '/mcp', '/hooks', '/sync', '/vistierie', '/.well-known/x']) {
      expect(navigate(p), `${p} must not be handled by the shell route`).toBe(false)
    }
  })

  it('never handles a non-navigation request', () => {
    const nav = workboxOptions.runtimeCaching.find((r) => r.handler === 'NetworkFirst')!
    expect(nav.urlPattern({ request: { mode: 'cors' } as Request, url: new URL('https://example.com/api/tools/call') })).toBe(false)
  })
})
