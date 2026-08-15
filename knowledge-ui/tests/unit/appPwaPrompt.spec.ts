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
import { __resetAuthMode } from '../../src/api/authMode'

// App.vue reads/writes vuetify's theme (useTheme()), so — like appAuthError.spec.ts —
// this instance needs the app's real theme names registered.
const testVuetify = createVuetify({
  components, directives,
  theme: { defaultTheme: 'hivememDark', themes: { hivememDark: { dark: true, colors: {} }, hivememLight: { dark: false, colors: {} } } },
})

const globalOpts = {
  global: {
    plugins: [i18n, testVuetify],
    stubs: {
      AppShell: true,
      VSnackbar: { template: '<div><slot /><slot name="actions" /></div>' },
      PwaReloadPrompt: { template: '<div class="pwa-prompt-stub" />' },
    },
  },
}

// The prompt that activates a waiting service worker used to live inside AppShell, which
// only mounts when auth.isAuthenticated. In the stale-worker failure mode (see
// documentation/auth.md) the user never authenticates, so the one control that could have
// healed the stale worker was unreachable. It must render regardless of the auth gate.
describe('App.vue PWA reload prompt reachability', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    resetApi()
    __resetAuthMode()
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'legacy' }))))
  })
  afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('renders the update prompt while the user is unauthenticated', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') throw new Error('HTTP 500')
      return {}
    })
    const w = mount(App, globalOpts)
    await flushPromises()
    expect(useAuthStore().isAuthenticated).toBe(false)
    expect(w.find('.pwa-prompt-stub').exists()).toBe(true)
  })

  it('still renders the update prompt once the user is authenticated', async () => {
    vi.spyOn(MockApiClient.prototype, 'call').mockImplementation(async (tool: string) => {
      if (tool === 'wake_up') return { role: 'admin', identity: 'me' }
      return {}
    })
    const w = mount(App, globalOpts)
    await flushPromises()
    expect(useAuthStore().isAuthenticated).toBe(true)
    expect(w.find('.pwa-prompt-stub').exists()).toBe(true)
  })
})
