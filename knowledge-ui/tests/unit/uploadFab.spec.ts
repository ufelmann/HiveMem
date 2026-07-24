import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../../src/api/uploadClient', async () => {
  const actual = await vi.importActual<any>('../../src/api/uploadClient')
  return { ...actual, uploadAttachment: vi.fn(() => Promise.resolve({ cellId: 'c1', deduplicated: false })) }
})

import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import { defineComponent, h } from 'vue'
import { vuetify } from '../../src/plugins/vuetify'
import { i18n } from '../../src/i18n'
import UploadFab from '../../src/components/shell/UploadFab.vue'
import { useUploadsStore } from '../../src/stores/uploads'

const Stage = defineComponent({ render: () => h('div', 'STAGE') })

// A stub router, not the app router: installing the real one kicks off the initial
// navigation, whose lazy route imports resolve after the test environment is torn
// down (EnvironmentTeardownError -> vitest exits 1 even with every test passing).
function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', name: 'search', component: Stage },
      { path: '/upload', name: 'upload', component: Stage },
    ],
  })
}

describe('UploadFab', () => {
  let router: ReturnType<typeof makeRouter>

  beforeEach(async () => {
    setActivePinia(createPinia())
    router = makeRouter()
    router.push('/')
    await router.isReady()
  })

  it('has a file input and a camera-capture input with correct attrs', () => {
    const w = mount(UploadFab, { global: { plugins: [vuetify, i18n, router] } })
    const file = w.get('[data-test="upload-fab-file"]')
    expect(file.attributes('accept')).toContain('image/')
    expect(file.attributes('accept')).toContain('application/pdf')
    expect(file.attributes('multiple')).toBeDefined()
    const cam = w.get('[data-test="upload-fab-camera"]')
    expect(cam.attributes('capture')).toBe('environment')
  })

  it('enqueues selected files into the store', async () => {
    const w = mount(UploadFab, { global: { plugins: [vuetify, i18n, router] } })
    const store = useUploadsStore()
    const input = w.get('[data-test="upload-fab-file"]').element as HTMLInputElement
    const dt = new DataTransfer()
    dt.items.add(new File(['a'], 'a.pdf', { type: 'application/pdf' }))
    Object.defineProperty(input, 'files', { value: dt.files })
    await w.get('[data-test="upload-fab-file"]').trigger('change')
    expect(store.jobs.length).toBe(1)
    expect(store.jobs[0].fileName).toBe('a.pdf')
  })
})
