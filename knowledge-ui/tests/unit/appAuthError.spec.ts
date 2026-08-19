import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import App from '../../src/App.vue'
import { i18n } from '../../src/i18n'
import { resetApi } from '../../src/api/useApi'
import { MockApiClient } from '../../src/api/mockClient'
import { useAuthStore } from '../../src/stores/auth'
import { useCanvasStore } from '../../src/stores/canvas'
import { useUiStore } from '../../src/stores/ui'
import { __resetAuthMode } from '../../src/api/authMode'
import { clearReauthGuard } from '../../src/api/reauth'

// The retry button must force past the re-auth guard (see api/reauth.ts, stores/auth.ts's
// logout()); stub clearReauthGuard so the test can observe whether the forced path
// actually called it, without depending on triggerReauth's window.location navigation.
vi.mock('../../src/api/reauth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/reauth')>()
  return { ...actual, clearReauthGuard: vi.fn() }
})

// App.vue reads/writes vuetify's theme (useTheme()), so — unlike other component
// tests that only need components/directives — this instance also needs the
// app's real theme names ('hivememDark'/'hivememLight') registered.
const testVuetify = createVuetify({
  components, directives,
  theme: { defaultTheme: 'hivememDark', themes: { hivememDark: { dark: true, colors: {} }, hivememLight: { dark: false, colors: {} } } },
})

const globalOpts = {
  global: {
    plugins: [i18n, testVuetify],
    // VSnackbar/VOverlay need `visualViewport`, which jsdom doesn't provide; stub
    // it with a plain element that still renders the default + actions slots so
    // the reload button is reachable.
    stubs: { AppShell: true, VSnackbar: { template: '<div><slot /><slot name="actions" /></div>' } },
  },
}

describe('App.vue auth error handling (E5)', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    __resetAuthMode()
    // App.vue loads the auth mode from /api/config before auth.init() — stub it so the
    // real (unmocked) fetch doesn't leave loadAuthMode() awaiting a live network call.
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'legacy' }))))
    vi.mocked(clearReauthGuard).mockClear()
  })
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('shows a retryable error state when auth.init() fails with a non-401 error, and recovers on retry', async () => {
    let call = 0
    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') {
        call++
        if (call === 1) throw new Error('HTTP 500')
        return { role: 'admin', identity: 'me' }
      }
      return {}
    })
    const w = mount(App, globalOpts)
    await flushPromises()
    expect(w.find('.splash.error').exists()).toBe(true)
    expect(useAuthStore().isAuthenticated).toBe(false)

    await w.find('.splash.error button').trigger('click')
    await flushPromises()
    expect(w.find('.splash.error').exists()).toBe(false)
    expect(useAuthStore().isAuthenticated).toBe(true)
    spy.mockRestore()
  })

  it('the snackbar reload action does not throw an unhandled rejection when canvas.loadTopLevel() rejects', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') return { role: 'admin', identity: 'me' }
      if (tool === 'list') throw new Error('boom')
      return {}
    })
    const w = mount(App, globalOpts)
    await flushPromises()
    const ui = useUiStore()
    ui.pushToast('info', 'test toast')
    await flushPromises()
    const btn = w.findAll('button').find(b => b.text() === 'Neu laden' || b.text() === 'Reload')
    expect(btn).toBeTruthy()
    await btn!.trigger('click')
    await flushPromises()
    // No assertion needed beyond "didn't throw" — vitest fails the run on an
    // unhandled rejection surfacing from this click.
    void useCanvasStore()
  })

  it('the retry button drops the re-auth guard, the mounted path does not', async () => {
    const clearReauthGuardMock = vi.mocked(clearReauthGuard)
    const spy = vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') throw new Error('HTTP 500')
      return {}
    })
    // clearReauthGuard is the observable seam: forcing a retry must clear the stamp
    // that would otherwise swallow the next triggerReauth call.
    expect(clearReauthGuardMock).not.toHaveBeenCalled()

    const wrapper = mount(App, globalOpts)
    await flushPromises()
    expect(clearReauthGuardMock).not.toHaveBeenCalled()   // onMounted keeps the guard
    expect(wrapper.find('.splash.error').exists()).toBe(true)

    await wrapper.find('[data-test="auth-retry"]').trigger('click')
    await flushPromises()
    expect(clearReauthGuardMock).toHaveBeenCalledOnce()    // the button forces
    spy.mockRestore()
  })
})
