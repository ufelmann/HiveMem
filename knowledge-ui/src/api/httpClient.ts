import type { ApiClient, HiveEvent, StatusSummary } from './types'
import { authMode } from './authMode'
import { triggerReauth } from './reauth'

export interface HttpApiConfig {
  endpoint: string
  token: string
  pollMs?: number
  /** Per-request timeout in ms (default 30s) — a hung backend must not leave loading flags stuck forever. */
  timeoutMs?: number
}

export class HttpApiClient implements ApiClient {
  private nextId = 1
  private subscribers = new Set<(e: HiveEvent) => void>()
  private timer: number | null = null
  private lastActivity: string | null = null

  private config: HttpApiConfig

  constructor(config: HttpApiConfig) {
    this.config = config
  }

  async call<T>(tool: string, args: Record<string, unknown> = {}): Promise<T> {
    const id = this.nextId++
    const headers: Record<string, string> = {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
    }
    if (this.config.token) {
      headers['Authorization'] = `Bearer ${this.config.token}`
    }
    const body = JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name: tool, arguments: args } })
    let res: Response
    try {
      res = await fetch(this.config.endpoint, {
        method: 'POST',
        credentials: 'same-origin',
        // Without 'manual' the browser follows Cloudflare's redirect to the Access login
        // page and the interception is invisible here — the opaqueredirect check below
        // was dead code until this line existed.
        redirect: 'manual',
        headers,
        body,
        signal: AbortSignal.timeout(this.config.timeoutMs ?? 30_000)
      })
    } catch (err) {
      // An Access interception no longer lands here: with redirect: 'manual' the edge's
      // cross-origin redirect surfaces as an opaqueredirect *response*, handled below. What
      // reaches this branch is a genuine transport failure — offline, DNS, TLS, connection
      // reset — or the AbortSignal timeout above.
      //
      // In legacy mode that is still worth a re-auth: a session that expired behind a
      // reverse proxy can present as a failed request, and /login is served by the origin
      // the browser just failed to reach only if it is actually up, so the worst case is
      // the same error one navigation later. In Access mode /login is a full navigation
      // into the edge, so doing it while the network is down replaces App.vue's retryable
      // error screen with a browser error page. Rethrow instead and let the SPA retry.
      if (authMode() === 'legacy') {
        triggerReauth('legacy')
        throw new Error('re-authenticating')
      }
      throw err
    }
    // The edge intercepted the request — re-auth is exactly what is needed, in both modes.
    if (res.type === 'opaqueredirect') {
      triggerReauth(authMode())
      throw new Error('re-authenticating')
    }
    // A 401 comes from the origin. In legacy mode that means "no session, go log in". In
    // Access mode the Access JWT was already accepted and the 401 means HumanPrincipalResolver
    // found no api_tokens row for that identity — re-authenticating cannot fix it and would
    // just bounce the user in a circle. Fall through so the error surfaces (App.vue renders
    // its retryable "connection failed" screen).
    if (res.status === 401 && authMode() === 'legacy') {
      triggerReauth('legacy')
      throw new Error('re-authenticating')
    }
    if (!res.ok) {
      // The backend often returns a JSON-RPC error body even on non-2xx — surface
      // its message instead of an opaque status code (L-F7).
      let msg = `HTTP ${res.status}`
      try {
        const body = await res.json() as { error?: { message?: string } }
        if (body?.error?.message) msg = body.error.message
      } catch { /* non-JSON error body — keep the status fallback */ }
      throw new Error(msg)
    }
    const json = await res.json() as {
      result?: { content?: Array<{ text?: string; type?: string }> }
      error?: { message: string }
    }
    if (json.error) throw new Error(json.error.message)
    const text = json.result?.content?.[0]?.text
    if (text === undefined) return undefined as T
    try {
      return JSON.parse(text) as T
    } catch {
      return text as T
    }
  }

  subscribe(onEvent: (e: HiveEvent) => void): () => void {
    this.subscribers.add(onEvent)
    if (!this.timer) this.startPolling()
    return () => {
      this.subscribers.delete(onEvent)
      if (this.subscribers.size === 0 && this.timer) {
        clearInterval(this.timer); this.timer = null
      }
    }
  }

  private startPolling() {
    const interval = this.config.pollMs ?? 10_000
    this.timer = setInterval(async () => {
      try {
        const s = await this.call<StatusSummary>('status')
        if (this.lastActivity && s.last_activity !== this.lastActivity) {
          this.subscribers.forEach(sub => sub({ type: 'status', last_activity: s.last_activity }))
        }
        this.lastActivity = s.last_activity
      } catch { /* swallow — next tick will retry */ }
    }, interval) as unknown as number
  }
}
