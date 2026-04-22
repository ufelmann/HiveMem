export function colorForRelation(relation: string) {
  return {
    related_to: '#9aa5ff',
    builds_on: '#4dc4ff',
    contradicts: '#ff4d4d',
    refines: '#4dff9c'
  }[relation] ?? '#7f8aa3'
}
