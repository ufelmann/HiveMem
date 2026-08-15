import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { HttpApiClient } from '../../src/api/httpClient'
import { loadAuthMode, __resetAuthMode } from '../../src/api/authMode'
import { triggerReauth, clearReauthGuard } from '../../src/api/reauth'

// triggerReauth navigates the real window; stub it so the assertions can look at the mode
// it was handed instead of at a location assignment happy-dom cannot intercept.
vi.mock('../../src/api/reauth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/reauth')>()
  return { ...actual, triggerReauth: vi.fn() }
})

describe('HttpApiClient', () => {
  beforeEach(() => { vi.restoreAllMocks() })

  it('sends JSON-RPC with bearer token', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      const headers = init.headers as Record<string, string>
      expect(headers['Authorization']).toBe('Bearer test-token')
      const body = JSON.parse(init.body as string)
      expect(body.method).toBe('tools/call')
      expect(body.params.name).toBe('status')
      // Real MCP tools/call envelope: payload is JSON in result.content[0].text
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: body.id,
        result: { content: [{ type: 'text', text: JSON.stringify({ drawer_count: 42 }) }] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const c = new HttpApiClient({ endpoint: '/mcp', token: 'test-token' })
    const r = await c.call<{ drawer_count: number }>('status')
    expect(r.drawer_count).toBe(42)
  })

  it('surfaces the backend JSON-RPC error message on non-2xx responses (L-F7)', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(
      JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32603, message: 'embedding service unavailable' } }),
      { status: 500 },
    )))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('search')).rejects.toThrow('embedding service unavailable')
  })

  it('falls back to the HTTP status for non-JSON error bodies', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response('<html>bad gateway</html>', { status: 502 })))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('status')).rejects.toThrow('HTTP 502')
  })

  it('attaches an abort/timeout signal so requests cannot hang forever (L-F7)', async () => {
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      expect(init.signal).toBeTruthy()
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }] },
      }))
    })
    vi.stubGlobal('fetch', fetchMock)
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await c.call('status')
    expect(fetchMock).toHaveBeenCalledOnce()
  })

  it('throws on JSON-RPC error', async () => {
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({ jsonrpc: '2.0', id: 1, error: { code: -32601, message: 'Method not found' } }))
    ))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't' })
    await expect(c.call('bogus')).rejects.toThrow('Method not found')
  })

  it('subscribe polls status and emits on change', async () => {
    let t = '2026-04-19T12:00:00Z'
    // Real MCP tools/call envelope: status payload is JSON in result.content[0].text
    vi.stubGlobal('fetch', vi.fn(async () =>
      new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1,
        result: { content: [{ type: 'text', text: JSON.stringify({ last_activity: t }) }] },
      }))
    ))
    const c = new HttpApiClient({ endpoint: '/mcp', token: 't', pollMs: 10 })
    const events: string[] = []
    const unsub = c.subscribe(e => events.push(e.type))
    await new Promise(r => setTimeout(r, 30))
    t = '2026-04-19T12:00:05Z'
    await new Promise(r => setTimeout(r, 30))
    unsub()
    expect(events).toContain('status')
  })
})

// Re-auth routing per deployment mode — see documentation/auth.md.
describe('HttpApiClient re-auth handling', () => {
  beforeEach(() => {
    vi.mocked(triggerReauth).mockClear()
    __resetAuthMode()
    clearReauthGuard()
    sessionStorage.clear()
  })
  afterEach(() => { vi.unstubAllGlobals(); __resetAuthMode() })

  async function primeMode(mode: 'access' | 'legacy') {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: mode }))))
    await loadAuthMode()
  }

  it('issues its fetch with redirect: manual so an Access redirect stays visible', async () => {
    let seen: RequestInit | undefined
    vi.stubGlobal('fetch', vi.fn(async (_url: string, init: RequestInit) => {
      seen = init
      return new Response(JSON.stringify({
        jsonrpc: '2.0', id: 1, result: { content: [{ type: 'text', text: '{}' }] },
      }))
    }))
    await new HttpApiClient({ endpoint: '/mcp', token: 't' }).call('status')
    expect(seen?.redirect).toBe('manual')
  })

  it('re-authenticates on an opaqueredirect in access mode', async () => {
    await primeMode('access')
    vi.stubGlobal('fetch', vi.fn(async () => ({ type: 'opaqueredirect', ok: false, status: 0 })))
    await expect(new HttpApiClient({ endpoint: '/mcp', token: 't' }).call('status'))
      .rejects.toThrow('re-authenticating')
    expect(triggerReauth).toHaveBeenCalledWith('access')
  })

  it('re-authenticates on a 401 in legacy mode', async () => {
    await primeMode('legacy')
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 401 })))
    await expect(new HttpApiClient({ endpoint: '/mcp', token: 't' }).call('status'))
      .rejects.toThrow('re-authenticating')
    expect(triggerReauth).toHaveBeenCalledWith('legacy')
  })

  // In Access mode a 401 means the edge JWT was accepted but the origin has no api_tokens
  // row for that identity. Re-auth cannot fix that, so the error must surface (App.vue's
  // retryable `common.connectError` screen) instead of navigating the user in a circle.
  it('throws instead of re-authenticating on a 401 in access mode', async () => {
    await primeMode('access')
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 401 })))
    await expect(new HttpApiClient({ endpoint: '/mcp', token: 't' }).call('status'))
      .rejects.toThrow('HTTP 401')
    expect(triggerReauth).not.toHaveBeenCalled()
  })

  // With redirect: 'manual' an Access interception arrives as an opaqueredirect response,
  // never as a throw — so the catch branch only sees transport failures and the abort
  // timeout. Legacy still re-auths there (an expired session behind a proxy can look like
  // a failed request); Access must not, or an offline blip navigates the user off the SPA
  // and onto a browser error page instead of the retryable screen.
  it('re-authenticates on a network failure in legacy mode', async () => {
    await primeMode('legacy')
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await expect(new HttpApiClient({ endpoint: '/mcp', token: 't' }).call('status'))
      .rejects.toThrow('re-authenticating')
    expect(triggerReauth).toHaveBeenCalledWith('legacy')
  })

  it('rethrows a network failure in access mode instead of re-authenticating', async () => {
    await primeMode('access')
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    await expect(new HttpApiClient({ endpoint: '/mcp', token: 't' }).call('status'))
      .rejects.toThrow('Failed to fetch')
    expect(triggerReauth).not.toHaveBeenCalled()
  })
})
