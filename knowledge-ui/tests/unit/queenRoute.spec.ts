import { describe, it, expect, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import QueenRoute from '../../src/pages/QueenRoute.vue'
import { resetApi } from '../../src/api/useApi'
import { i18n } from '../../src/i18n'

const opts = { global: { plugins: [i18n], stubs: { HmIcon: true } } }

function dataRows(w: any) {
  return w.findAll('.qtable .qrow').filter((r: any) => !r.classes('qhead'))
}
async function mountReady() {
  const w = mount(QueenRoute, opts)
  for (let i = 0; i < 60 && dataRows(w).length === 0; i++) {
    await new Promise(r => setTimeout(r, 25)); await flushPromises()
  }
  return w
}

async function mountIngestReady() {
  const w = await mountReady()
  for (let i = 0; i < 60 && !w.find('.q-ingest').exists(); i++) {
    await new Promise(r => setTimeout(r, 25)); await flushPromises()
  }
  return w
}

describe('QueenRoute (restyled)', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'de'
    setActivePinia(createPinia())
    localStorage.setItem('hivemem_mock', 'true')
    localStorage.removeItem('hivemem_mock_ingest_scenario')
    resetApi()
  })

  it('renders KPIs and one run row per run with a status pill, no Vuetify', async () => {
    const w = await mountReady()
    expect(w.find('.kpi').exists()).toBe(true)
    expect(dataRows(w).length).toBeGreaterThanOrEqual(3)
    expect(w.text()).toContain('isolated-cell-bee')
    expect(w.findAll('.qstatus').length).toBeGreaterThanOrEqual(3)
    expect(w.html()).not.toContain('v-table')
  })

  it('renders proposal cards and accepting one removes it', async () => {
    const w = await mountReady()
    const before = w.findAll('.prop-card').length
    expect(before).toBeGreaterThanOrEqual(1)
    await w.find('.prop-card .prop-actions .btn').trigger('click')
    for (let i = 0; i < 60 && w.findAll('.prop-card').length === before; i++) {
      await new Promise(r => setTimeout(r, 25)); await flushPromises()
    }
    expect(w.findAll('.prop-card').length).toBe(before - 1)
  })

  it('opens the run-detail overlay with summary on row click', async () => {
    const w = await mountReady()
    await dataRows(w)[0].trigger('click')
    for (let i = 0; i < 60 && !w.find('.q-detail').exists(); i++) {
      await new Promise(r => setTimeout(r, 25)); await flushPromises()
    }
    const ov = w.find('.q-detail')
    expect(ov.exists()).toBe(true)
    expect(ov.text()).toContain('Surveyed')
  })

  describe('ingest queue section', () => {
    it('renders failed files and degraded batches when the queue is populated', async () => {
      const w = await mountIngestReady()
      const section = w.find('.q-ingest')
      expect(section.exists()).toBe(true)
      expect(section.text()).toContain('scan-0001.pdf')
      expect(section.find('.notice').exists()).toBe(false)
      expect(section.findAll('.ingest-row').length).toBeGreaterThanOrEqual(2)
    })

    it('shows "nothing to review" for a healthy empty queue, with no rows', async () => {
      localStorage.setItem('hivemem_mock_ingest_scenario', 'empty')
      resetApi()
      const w = await mountIngestReady()
      const section = w.find('.q-ingest')
      expect(section.exists()).toBe(true)
      expect(section.find('.notice').exists()).toBe(false)
      expect(section.findAll('.ingest-row').length).toBe(0)
      expect(section.text()).toContain('Nichts zu prüfen')
    })

    it('shows the distinct unavailable notice and renders no tables when disabled', async () => {
      localStorage.setItem('hivemem_mock_ingest_scenario', 'unavailable')
      resetApi()
      const w = await mountIngestReady()
      const section = w.find('.q-ingest')
      expect(section.exists()).toBe(true)
      expect(section.find('.notice').exists()).toBe(true)
      expect(section.text()).toContain('nicht verfügbar')
      expect(section.findAll('.ingest-row').length).toBe(0)
      expect(section.text()).not.toContain('Nichts zu prüfen')
    })
  })
})
