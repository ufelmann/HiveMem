import { describe, it, expect } from 'vitest'

describe('graph route', () => {
  it('registers a /graph route', async () => {
    const { router } = await import('../../src/router')
    const match = router.resolve('/graph')
    expect(match.name).toBe('graph')
  })
})
