// Offline fixture for the frontend's mock mode (see api/useApi.ts, api/mockClient.ts).
//
// IMPORTANT: this module ships in a public repository. Every cell, tunnel, fact and
// reference below MUST be invented — no real names, phone numbers, postal addresses,
// email addresses or internal infrastructure details. Use fictional entities such as
// "Nordwind Logistik GmbH" and "Stadtverwaltung Musterstadt", and example.com-style
// contact details only. A previous version of this file leaked a real person's phone
// numbers and had to be purged from git history — do not repeat that mistake.
import type { Cell, Realm, Signal, Topic, Tunnel, Fact, Reference } from '../api/types'

const cells: Cell[] = [
  {
    id: 'doc-contract-001',
    realm: 'documents',
    signal: 'facts',
    topic: 'contracts',
    title: 'Delivery contract with Nordwind Logistik GmbH',
    content: 'Contract AZ 2026/0815 covering monthly delivery services between the Musterstadt warehouse and regional depots.',
    summary: 'Delivery contract, effective 2026, file number AZ 2026/0815.',
    key_points: ['Monthly delivery schedule', 'File number AZ 2026/0815'],
    insight: 'Renewal notice period is three months before the contract anniversary.',
    tags: ['contract', 'logistics'],
    importance: 2,
    status: 'committed',
    created_by: 'mock-user',
    created_at: '2026-01-10T09:00:00Z',
    valid_from: '2026-01-10T09:00:00Z',
    valid_until: null,
  },
  {
    id: 'note-permit-001',
    realm: 'personal',
    signal: 'events',
    topic: 'permits',
    title: 'Building permit application filed',
    content: 'Filed a building permit application with the Stadtverwaltung Musterstadt for a garden shed extension.',
    summary: 'Building permit application filed with Stadtverwaltung Musterstadt.',
    key_points: ['Application filed', 'Awaiting decision'],
    insight: null,
    tags: ['permit', 'municipal'],
    importance: 1,
    status: 'committed',
    created_by: 'mock-user',
    created_at: '2026-02-03T14:30:00Z',
    valid_from: '2026-02-03T14:30:00Z',
    valid_until: null,
  },
  {
    id: 'note-idea-001',
    realm: null,
    signal: null,
    topic: null,
    title: 'Idea: route optimization dashboard',
    content: 'Sketch of a dashboard idea for visualizing delivery route efficiency across regional depots.',
    summary: 'Unclassified idea about a route optimization dashboard.',
    key_points: ['Not yet classified'],
    insight: null,
    tags: ['idea'],
    importance: 3,
    status: 'pending',
    created_by: 'mock-user',
    created_at: '2026-03-15T08:00:00Z',
    valid_from: '2026-03-15T08:00:00Z',
    valid_until: null,
  },
]

const tunnels: Tunnel[] = [
  {
    id: 'tun-001',
    from_cell: 'doc-contract-001',
    to_cell: 'note-permit-001',
    relation: 'related_to',
    note: 'Both relate to dealings with Musterstadt administrative bodies.',
    status: 'committed',
    created_at: '2026-02-04T10:00:00Z',
    valid_until: null,
  },
]

const facts: Fact[] = [
  {
    id: 'fact-001',
    subject: 'doc-contract-001',
    predicate: 'vendor',
    object: 'Nordwind Logistik GmbH',
    valid_from: '2026-01-10T09:00:00Z',
    valid_until: null,
  },
]

const references: Reference[] = [
  {
    id: 'ref-001',
    title: 'Guide to municipal permit procedures',
    url: 'https://example.com/permits/guide',
    ref_type: 'article',
    status: 'unread',
  },
]

const realms: Realm[] = (() => {
  const realmMap = new Map<string, Realm>()
  for (const cell of cells) {
    if (cell.realm === null) continue
    let realm = realmMap.get(cell.realm)
    if (!realm) {
      realm = { name: cell.realm, cell_count: 0, signals: [] }
      realmMap.set(cell.realm, realm)
    }
    realm.cell_count++

    const signalName = cell.signal ?? '(none)'
    let signal = realm.signals.find((s): s is Signal => s.name === signalName)
    if (!signal) {
      signal = { name: signalName, cell_count: 0, topics: [] }
      realm.signals.push(signal)
    }
    signal.cell_count++

    const topicName = cell.topic ?? '(none)'
    let topic = signal.topics.find((t): t is Topic => t.name === topicName)
    if (!topic) {
      topic = { name: topicName, cell_count: 0 }
      signal.topics.push(topic)
    }
    topic.cell_count++
  }
  return [...realmMap.values()]
})()

export const palace = { cells, realms, tunnels, facts, references }
