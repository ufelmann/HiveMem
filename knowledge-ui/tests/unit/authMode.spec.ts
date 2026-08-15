import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { loadAuthMode, authMode, __resetAuthMode } from '../../src/api/authMode'

describe('authMode', () => {
  beforeEach(() => __resetAuthMode())
  afterEach(() => vi.unstubAllGlobals())

  it('resolves to access when /api/config answers authMode: access', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'access' }))))
    const m = await loadAuthMode()
    expect(m).toBe('access')
    expect(authMode()).toBe('access')
  })

  it('resolves to legacy when /api/config answers authMode: legacy', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'legacy' }))))
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
  })

  it('falls back to legacy when the fetch rejects', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
  })

  // Regression (whole-branch e2e review): a slow/unreachable backend (dev-proxy to a down
  // backend, offline page load) must not hang App.vue's startup forever. loadAuthMode()
  // bounds the fetch with AbortSignal.timeout(1500) — an AbortError from that timeout must
  // land in the same catch as any other fetch failure and resolve to 'legacy' quickly.
  it('falls back to legacy fast when the fetch times out / aborts', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new DOMException('The operation was aborted.', 'AbortError')
    }))
    const start = Date.now()
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
    expect(Date.now() - start).toBeLessThan(200)
  })

  it('caches the mode and does not fetch again on a second call', async () => {
    const fetchMock = vi.fn(async () => new Response(JSON.stringify({ authMode: 'access' })))
    vi.stubGlobal('fetch', fetchMock)
    await loadAuthMode()
    await loadAuthMode()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  // Residual flake (whole-branch e2e review, round 2): in mock mode there is no backend to
  // answer /api/config, so waiting out the 1.5s timeout delayed every mock/offline page load
  // (mount used to be instant pre-fix). Mock mode is legacy by definition and never exercises
  // authMode() for a real decision, so skip the fetch entirely.
  it('short-circuits to legacy without fetching when hivemem_mock is set', async () => {
    localStorage.setItem('hivemem_mock', 'true')
    const fetchMock = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const m = await loadAuthMode()
    expect(m).toBe('legacy')
    expect(fetchMock).not.toHaveBeenCalled()
    localStorage.removeItem('hivemem_mock')
  })

  // An expired Cloudflare Access session answers /api/config with a cross-origin 302. With
  // redirect: 'manual' that surfaces as an opaqueredirect whose body is unreadable —
  // classifying it as 'legacy' was what sent the app down the wrong re-auth path.
  // See documentation/auth.md.
  it('resolves to access on an opaqueredirect without reading the body', async () => {
    const json = vi.fn()
    // Response.type is a read-only getter and 'opaqueredirect' is not constructible
    // under happy-dom, so stub the shape the branch actually inspects.
    vi.stubGlobal('fetch', vi.fn(async () => ({ type: 'opaqueredirect', ok: false, status: 0, json })))
    expect(await loadAuthMode()).toBe('access')
    expect(json).not.toHaveBeenCalled()
  })

  // Defensive second branch: with redirect: 'manual' a browser hands back an opaqueredirect,
  // never a readable 3xx, so this status check cannot fire in a real browser. It covers a
  // non-browser fetch (a polyfill, a test runner, a future proxying layer) that does surface
  // the status, so the classifier does not silently answer 'legacy' there.
  it('resolves to access on a readable 3xx status, not just on opaqueredirect', async () => {
    const json = vi.fn()
    vi.stubGlobal('fetch', vi.fn(async () => ({ type: 'basic', ok: false, status: 302, json })))
    expect(await loadAuthMode()).toBe('access')
    expect(json).not.toHaveBeenCalled()
  })

  it('resolves to access on 401', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 401 })))
    expect(await loadAuthMode()).toBe('access')
  })

  it('resolves to access on 403', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 403 })))
    expect(await loadAuthMode()).toBe('access')
  })

  // authMode() also picks the logout URL (stores/auth.ts) and the dev-token escape
  // hatch (useApi.ts), so a transient origin 5xx must not flip the mode for the rest of
  // the page load. This is why the classifier is not `!res.ok`.
  it('does not resolve to access on a 5xx', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>bad gateway</html>', { status: 502 })))
    expect(await loadAuthMode()).toBe('legacy')
  })

  // The classifier can only see an opaqueredirect if the fetch opts out of the browser's
  // automatic redirect following.
  it('issues the config fetch with redirect: manual', async () => {
    let seen: RequestInit | undefined
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      seen = init
      return new Response(JSON.stringify({ authMode: 'legacy' }))
    }))
    await loadAuthMode()
    expect(seen?.redirect).toBe('manual')
  })
})
