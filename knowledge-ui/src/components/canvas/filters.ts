import { AdvancedBloomFilter, OutlineFilter, ZoomBlurFilter, GodrayFilter } from 'pixi-filters'

export const focusFilter = () =>
  new AdvancedBloomFilter({
    threshold: 0.25,
    bloomScale: 1.4,
    brightness: 1,
    blur: 8,
    quality: 5
  })

export const hoverFilter = () =>
  new AdvancedBloomFilter({
    threshold: 0.4,
    bloomScale: 0.8,
    brightness: 1,
    blur: 4,
    quality: 3
  })

export const focusRing = () => new OutlineFilter({ color: 0x4dc4ff, thickness: 2 })

export const snapPulse = () => new ZoomBlurFilter({ strength: 0.25 })

export const godrays = () =>
  new GodrayFilter({ alpha: 0.05, angle: 30, lacunarity: 2.75, gain: 0.3 })
