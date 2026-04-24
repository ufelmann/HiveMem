import type { ApiClient, HiveEvent, StatusSummary } from './types'

export interface HttpApiConfig {
  endpoint: string
  token: string
  pollMs?: number
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
    const res = await fetch(this.config.endpoint, {
      method: 'POST',
      headers,
      body: JSON.stringify({ jsonrpc: '2.0', id, method: 'tools/call', params: { name: tool, arguments: args } })
    })
    if (res.status === 401) {
      window.location.href = '/login'
      throw new Error('Session expired')
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
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
