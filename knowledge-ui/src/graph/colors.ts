import type { Relation } from '../api/types'

const relationColors: Record<Relation, string> = {
  related_to: '#9aa5ff',
  builds_on: '#4dc4ff',
  contradicts: '#ff4d4d',
  refines: '#4dff9c'
}

export function colorForRelation(relation: Relation) {
  return relationColors[relation]
}

export function colorForRealm(name: string): string {
  let h = 0
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) % 360
  return `hsl(${h}, 70%, 55%)`
}
