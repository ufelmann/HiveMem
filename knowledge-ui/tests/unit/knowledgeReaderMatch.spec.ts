import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import KnowledgeReader from '../../src/components/knowledge/KnowledgeReader.vue'
import { i18n } from '../../src/i18n'
import { useKnowledgeSearch, __resetKnowledgeSearch } from '../../src/composables/useKnowledgeSearch'
import type { SearchResult } from '../../src/api/types'

// Design §3.7: a search hit shows "Seite N" / "Seiten N–M" only when the matching chunk
// carried page numbers, otherwise just the excerpt, and falls back to the summary when
// there's no chunk match at all (the cell-vector-only case).
function baseHit(overrides: Partial<SearchResult>): SearchResult {
  return {
    id: 'c1', realm: 'docs', signal: 'facts', topic: null, title: 'Bausparvertrag',
    content: 'VOLLTEXT', summary: 'Eine Zusammenfassung', key_points: [], insight: null,
    tags: [], importance: 2, status: 'committed', created_by: 'u', created_at: '2024-01-01',
    valid_from: '2024-01-01', valid_until: null, score_total: 0.8,
    ...overrides,
  }
}

describe('KnowledgeReader search hit match display', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    i18n.global.locale.value = 'de'
    __resetKnowledgeSearch()
  })

  it('shows a single page label for a chunk match with page_from only', () => {
    const { results } = useKnowledgeSearch()
    results.value = [baseHit({
      match: { page_from: 12, excerpt: 'Zusammenlegung und Teilung der Bausparsumme…' },
    })]
    const w = mount(KnowledgeReader, { global: { plugins: [i18n] } })

    const row = w.get('[data-test="row-match"]')
    expect(row.get('[data-test="row-match-page"]').text()).toBe('Seite 12')
    expect(row.text()).toContain('Zusammenlegung und Teilung der Bausparsumme…')
  })

  it('shows a page range for page_from/page_to', () => {
    const { results } = useKnowledgeSearch()
    results.value = [baseHit({
      match: { page_from: 12, page_to: 13, excerpt: 'Fundstelle über zwei Seiten' },
    })]
    const w = mount(KnowledgeReader, { global: { plugins: [i18n] } })

    expect(w.get('[data-test="row-match-page"]').text()).toBe('Seiten 12–13')
  })

  it('shows the excerpt alone, without a page label, when no page numbers are present', () => {
    const { results } = useKnowledgeSearch()
    results.value = [baseHit({
      match: { excerpt: 'Fundstueck ohne Marker im Rohtext' },
    })]
    const w = mount(KnowledgeReader, { global: { plugins: [i18n] } })

    const row = w.get('[data-test="row-match"]')
    expect(row.find('[data-test="row-match-page"]').exists()).toBe(false)
    expect(row.text()).toContain('Fundstueck ohne Marker im Rohtext')
    expect(row.text()).not.toContain('null')
    expect(row.text()).not.toMatch(/Seite/)
  })

  it('falls back to the summary when the hit has no match at all', () => {
    const { results } = useKnowledgeSearch()
    results.value = [baseHit({ summary: 'Nur ueber den Zellvektor gefunden' })]
    const w = mount(KnowledgeReader, { global: { plugins: [i18n] } })

    expect(w.find('[data-test="row-match"]').exists()).toBe(false)
    expect(w.text()).toContain('Nur ueber den Zellvektor gefunden')
  })
})
