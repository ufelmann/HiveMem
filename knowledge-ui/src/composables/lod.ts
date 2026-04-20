export type Lod = 'realms' | 'halos' | 'labels' | 'full'

export function lodLevel(zoom: number): Lod {
  if (zoom < 0.3) return 'realms'
  if (zoom < 1.0) return 'halos'
  if (zoom < 3.0) return 'labels'
  return 'full'
}

export function cellVisibleAt(zoom: number) { return zoom >= 0.3 }

export function labelsVisibleAt(zoom: number) { return zoom >= 1.0 }

export function edgeAlphaAt(zoom: number) { return Math.max(0.1, Math.min(1, zoom / 2)) }
