import { describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUiStore } from '../../src/stores/ui'
import { registerType } from '../../vite.config'

describe('PWA update handling', () => {
  it('the ui store carries no service-worker update state', () => {
    setActivePinia(createPinia())
    const ui = useUiStore() as unknown as Record<string, unknown>
    // autoUpdate activates the new worker itself; a leftover prompt path would be a
    // second, contradictory way to update and could strand a user on an old shell.
    expect(ui.swUpdateReady).toBeUndefined()
    expect(ui.setSwUpdate).toBeUndefined()
    expect(ui.applySwUpdate).toBeUndefined()
  })

  it('registers the worker in autoUpdate mode', () => {
    // Read from the named export, not the plugin instance: vite-plugin-pwa@1.3.0 does not
    // expose api.options (see pwaConfig.spec.ts), so vite.config.ts exports the value
    // directly the same way it exports workboxOptions.
    expect(registerType).toBe('autoUpdate')
  })
})
