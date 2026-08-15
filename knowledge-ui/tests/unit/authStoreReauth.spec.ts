import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../../src/stores/auth'
import { MockApiClient } from '../../src/api/mockClient'
import { resetApi } from '../../src/api/useApi'
import { loadAuthMode, __resetAuthMode } from '../../src/api/authMode'
import { triggerReauth, clearReauthGuard } from '../../src/api/reauth'

// The store navigates the real window through triggerReauth; stub it so the options it
// passes stay inspectable.
vi.mock('../../src/api/reauth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/reauth')>()
  return { ...actual, triggerReauth: vi.fn() }
})

// How the store drives the shared re-auth path — see documentation/auth.md.
describe('auth store re-auth interaction', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    __resetAuthMode()
    clearReauthGuard()
    sessionStorage.clear()
    vi.mocked(triggerReauth).mockClear()
  })
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  async function primeMode(mode: 'access' | 'legacy') {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: mode }))))
    await loadAuthMode()
    vi.unstubAllGlobals()
  }

  // Logout is a deliberate user action. Guarded, it would return silently after any
  // earlier automatic re-auth and leave the user on a permanent splash — state already
  // cleared, no error set, nothing navigating.
  it('logout() forces the navigation past the guard in legacy mode', async () => {
    await primeMode('legacy')
    vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 204 })))
    await useAuthStore().logout()
    expect(triggerReauth).toHaveBeenCalledWith('legacy', undefined, { force: true })
  })

  // A healthy session must not leave a stamp behind that suppresses a later genuine
  // re-auth.
  it('a successful init() clears the guard stamp', async () => {
    await primeMode('legacy')
    sessionStorage.setItem('hivemem_reauth_at', String(Date.now()))
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') return { role: 'admin', identity: 'me' }
      return {}
    })
    await useAuthStore().init()
    expect(sessionStorage.getItem('hivemem_reauth_at')).toBeNull()
  })
})
