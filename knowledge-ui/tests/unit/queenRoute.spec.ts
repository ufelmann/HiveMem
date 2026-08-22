import { describe, it, expect, beforeEach, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, flushPromises, RouterLinkStub } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import QueenRoute from '../../src/pages/QueenRoute.vue'
import { resetApi } from '../../src/api/useApi'
import { i18n } from '../../src/i18n'
import { useQueenStore } from '../../src/stores/queen'
import { useUiStore } from '../../src/stores/ui'

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

  it('renders a proposal title and its rationale as separate fields', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    store.pending = [{
      type: 'tunnel', id: 'p-1',
      title: 'alpha/yoyo -[related_to]-> beta/yoyo',
      description: 'Both notes cover the same yoyo migration.',
      realm: 'alpha', signal: null,
      from_cell: '00000000-0000-0000-0000-0000000000a1',
      to_cell: '00000000-0000-0000-0000-0000000000b2',
      created_by: 'queen', created_at: '2026-06-02T03:00:13Z',
    }]
    await nextTick()
    const card = w.find('.prop-card')
    expect(card.find('.prop-title').text()).toBe('alpha/yoyo -[related_to]-> beta/yoyo')
    expect(card.find('.prop-detail').text()).toBe('Both notes cover the same yoyo migration.')
    // The old propTitle() heuristic sliced the description at the first colon; a title that is
    // now its own field must survive a colon in the text.
    expect(card.find('.prop-title').text()).not.toContain('Both notes')
  })

  it('links both endpoint cells of a tunnel proposal', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    store.pending = [{
      type: 'tunnel', id: 'p-1', title: 'alpha/yoyo -[related_to]-> beta/yoyo',
      description: 'Rationale.', realm: 'alpha', signal: null,
      from_cell: '00000000-0000-0000-0000-0000000000a1',
      to_cell: '00000000-0000-0000-0000-0000000000b2',
      created_by: 'queen', created_at: '2026-06-02T03:00:13Z',
    }]
    await nextTick()
    const links = w.findAllComponents(RouterLinkStub)
    expect(links).toHaveLength(2)
    expect(links[0].props('to')).toEqual({ name: 'search', query: { cell: '00000000-0000-0000-0000-0000000000a1' } })
    expect(links[1].props('to')).toEqual({ name: 'search', query: { cell: '00000000-0000-0000-0000-0000000000b2' } })
  })

  it('renders no body and no links for a proposal without a description or endpoints', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    store.pending = [{
      type: 'fact', id: 'p-2', title: 'HiveMem -> runs on -> Python',
      description: null, realm: null, signal: null,
      from_cell: null, to_cell: null,
      created_by: 'queen', created_at: '2026-06-02T03:00:14Z',
    }]
    await nextTick()
    // An empty paragraph leaves a gap that reads like a half-loaded card.
    expect(w.find('.prop-card .prop-detail').exists()).toBe(false)
    expect(w.find('.prop-card .prop-links').exists()).toBe(false)
  })

  it('arms the accept-all button before committing, and commits every listed id on confirm', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    const approveAll = vi.spyOn(store, 'approveAll').mockResolvedValue(2)
    store.pending = [
      { type: 'cell', id: 'p-1', title: 'a/b', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
      { type: 'cell', id: 'p-2', title: 'a/c', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
    await nextTick()

    expect(w.find('[data-test="accept-all"]').text()).toContain('2')
    await w.find('[data-test="accept-all"]').trigger('click')
    // One click must not commit: this is not undoable from the UI.
    expect(approveAll).not.toHaveBeenCalled()

    await w.find('[data-test="accept-all-confirm"]').trigger('click')
    expect(approveAll).toHaveBeenCalledTimes(1)
  })

  it('cancelling the accept-all confirmation commits nothing', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    const approveAll = vi.spyOn(store, 'approveAll').mockResolvedValue(0)
    store.pending = [
      { type: 'cell', id: 'p-1', title: 'a/b', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
    ]
    await nextTick()

    await w.find('[data-test="accept-all"]').trigger('click')
    await w.find('[data-test="accept-all-cancel"]').trigger('click')
    expect(approveAll).not.toHaveBeenCalled()
    expect(w.find('[data-test="accept-all"]').exists()).toBe(true)
  })

  it('disarms the accept-all confirmation when the pending list changes underneath it', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    const approveAll = vi.spyOn(store, 'approveAll').mockResolvedValue(0)
    store.pending = [
      { type: 'cell', id: 'p-1', title: 'a/b', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
    ]
    await nextTick()

    await w.find('[data-test="accept-all"]').trigger('click')
    expect(w.find('[data-test="accept-all-confirm"]').exists()).toBe(true)

    // Simulate the background poll (refreshSafe) repopulating the list independently of the
    // user's arm click — e.g. the rows were decided elsewhere, or a Queen run cycled them.
    store.pending = []
    await nextTick()
    store.pending = [
      { type: 'cell', id: 'p-2', title: 'a/c', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
    await nextTick()

    // The re-populated list must render the armed button, not a live confirm/cancel pair —
    // otherwise a single click on it would irreversibly commit a set the user never armed.
    expect(w.find('[data-test="accept-all"]').exists()).toBe(true)
    expect(w.find('[data-test="accept-all-confirm"]').exists()).toBe(false)
    expect(approveAll).not.toHaveBeenCalled()
  })

  it('disarms the accept-all confirmation when a poll grows the pending list while armed', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    const approveAll = vi.spyOn(store, 'approveAll').mockResolvedValue(0)
    store.pending = [
      { type: 'cell', id: 'p-1', title: 'a/b', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
    ]
    await nextTick()

    await w.find('[data-test="accept-all"]').trigger('click')
    expect(w.find('[data-test="accept-all-confirm"]').exists()).toBe(true)

    // A poll adds a proposal while armed: the confirm button would now commit a different,
    // larger set than the one the user armed against.
    store.pending = [
      ...store.pending,
      { type: 'cell', id: 'p-2', title: 'a/c', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
    await nextTick()

    expect(w.find('[data-test="accept-all"]').exists()).toBe(true)
    expect(w.find('[data-test="accept-all-confirm"]').exists()).toBe(false)
    expect(approveAll).not.toHaveBeenCalled()
  })

  it('reports the store\'s returned count in the toast, not pending.length', async () => {
    const w = mount(QueenRoute, {
      global: { plugins: [i18n], stubs: { HmIcon: true, RouterLink: RouterLinkStub } },
    })
    const store = useQueenStore()
    const ui = useUiStore()
    // Backend committed only 1 of the 2 listed ids (e.g. one was already decided elsewhere) —
    // the toast must honestly report what the backend returned, not the size of the request.
    vi.spyOn(store, 'approveAll').mockResolvedValue(1)
    store.pending = [
      { type: 'cell', id: 'p-1', title: 'a/b', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:13Z' },
      { type: 'cell', id: 'p-2', title: 'a/c', description: 's', realm: 'a', signal: null,
        from_cell: null, to_cell: null, created_by: 'queen', created_at: '2026-06-02T03:00:14Z' },
    ]
    await nextTick()

    await w.find('[data-test="accept-all"]').trigger('click')
    await w.find('[data-test="accept-all-confirm"]').trigger('click')
    await flushPromises()

    expect(ui.toast?.text).toContain('1')
    expect(ui.toast?.text).not.toContain('2 Vorschläge')
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

    it('renders stalled rows with their state and a retry button', async () => {
      const w = await mountIngestReady()
      const section = w.find('.q-ingest')
      // A row stuck in 'staged'/'processing' past the stale threshold used to exist only as an
      // anonymous integer in stateCounts — no filename, nothing to click.
      expect(section.text()).toContain('Hängengebliebene Dateien')
      expect(section.text()).toContain('scan-0003.pdf')
      expect(section.text()).toContain('processing')
      expect(section.findAll('.ingest-row').length).toBeGreaterThanOrEqual(3)
      expect(section.findAll('.ingest-retry').length).toBeGreaterThanOrEqual(3)
    })

    it('renders the non-terminal ledger states in the per-state census', async () => {
      const w = await mountIngestReady()
      const chips = w.findAll('.ingest-state-chip').map(c => c.text())
      expect(chips.join(' ')).toContain('staged')
      expect(chips.join(' ')).toContain('processing')
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

    // The blank-page-count span is the only one carrying a `title` attribute (the tooltip),
    // which lets these two tests target it precisely instead of scanning the whole row's text —
    // the row's other fields (degradedPages, totalPages) can themselves contain "0" or "3".
    function blankPagesCell(w: any) {
      return w.find('.q-ingest .ingest-row span[title]')
    }

    it('renders a degraded batch\'s blank page count', async () => {
      const w = await mountIngestReady()
      const store = useQueenStore()
      store.ingestQueue!.degradedBatches = [
        { sha256: 'bbbb0002', filename: 'scan-0002.pdf', totalPages: 40, degradedPages: 3,
          blankPages: 3, updatedAt: '2026-08-02T10:00:00Z' },
      ]
      await nextTick()
      expect(blankPagesCell(w).text()).toContain('3')
    })

    it('renders "—" instead of an invented 0 when blankPages is null (row predates V0055)', async () => {
      const w = await mountIngestReady()
      const store = useQueenStore()
      store.ingestQueue!.degradedBatches = [
        { sha256: 'bbbb0002', filename: 'scan-0002.pdf', totalPages: 40, degradedPages: 3,
          blankPages: null, updatedAt: '2026-08-02T10:00:00Z' },
      ]
      await nextTick()
      const cell = blankPagesCell(w)
      expect(cell.text()).toContain('—')
      expect(cell.text()).not.toContain('0')
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
