import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import { vuetify } from '../../src/plugins/vuetify'
import { i18n } from '../../src/i18n'
import UploadRoute from '../../src/pages/UploadRoute.vue'
import { useUploadsStore } from '../../src/stores/uploads'
import { loadAuthMode, __resetAuthMode } from '../../src/api/authMode'
import { triggerReauth } from '../../src/api/reauth'

// The re-login button navigates the real window through triggerReauth; stub it so the
// options it passes stay inspectable.
vi.mock('../../src/api/reauth', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../src/api/reauth')>()
  return { ...actual, triggerReauth: vi.fn() }
})

const Stage = defineComponent({ render: () => h('div', 'STAGE') })

// A stub router, not the app router — see uploadFab.spec.ts: the real router's
// initial navigation resolves lazy imports after environment teardown.
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: Stage },
      { path: '/upload', name: 'upload', component: Stage },
    ],
  })
}

describe('UploadRoute', () => {
  let g: { global: { plugins: [typeof vuetify, typeof i18n, ReturnType<typeof makeRouter>] } }

  beforeEach(async () => {
    setActivePinia(createPinia())
    // The resolved mode is module-level cache: reset it in a hook, not in a test body, so a
    // mid-test throw cannot leak it into the next test.
    __resetAuthMode()
    const router = makeRouter()
    router.push('/upload')
    await router.isReady()
    g = { global: { plugins: [vuetify, i18n, router] } }
  })

  afterEach(() => {
    __resetAuthMode()
    vi.unstubAllGlobals()
  })

  it('shows the empty hint when there are no jobs', () => {
    const w = mount(UploadRoute, g)
    expect(w.get('[data-test="upload-empty"]').isVisible()).toBe(true)
  })

  it('renders a row per job with its status', async () => {
    const w = mount(UploadRoute, g)
    const s = useUploadsStore()
    s.jobs.push({ id: 'u1', file: new File(['a'], 'a.pdf'), fileName: 'a.pdf', size: 3, status: 'done', progress: 1, retryable: false, result: { cellId: 'c1', deduplicated: false } })
    await w.vm.$nextTick()
    const rows = w.findAll('[data-test="upload-job"]')
    expect(rows.length).toBe(1)
    expect(rows[0].text()).toContain('a.pdf')
  })

  it('shows a re-login banner when authError is set', async () => {
    const w = mount(UploadRoute, g)
    useUploadsStore().authError = true
    await w.vm.$nextTick()
    expect(w.find('[data-test="upload-relogin"]').exists()).toBe(true)
  })

  // The re-login button is a deliberate user action and must not be swallowed by the
  // re-auth guard — see documentation/auth.md.
  it('forces the re-auth navigation past the guard when re-login is clicked', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => new Response(JSON.stringify({ authMode: 'access' }))))
    await loadAuthMode()
    vi.unstubAllGlobals()
    vi.mocked(triggerReauth).mockClear()

    // The app registers Vuetify's components through the vite plugin, not the shared
    // vuetify instance, so VBtn does not resolve under the test runner — stub it with a
    // plain button that forwards the click.
    const w = mount(UploadRoute, {
      global: {
        ...g.global,
        stubs: { VBtn: { template: '<button @click="$emit(\'click\')"><slot /></button>' } },
      },
    })
    useUploadsStore().authError = true
    await w.vm.$nextTick()
    await w.get('[data-test="upload-relogin"] button').trigger('click')
    expect(triggerReauth).toHaveBeenCalledWith('access', undefined, { force: true })
  })
})
