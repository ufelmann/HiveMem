import * as THREE from 'three'

export interface GoldbergCell {
  index: number
  sides: 5 | 6
  centroid: [number, number, number]
  vertices: [number, number, number][]
  neighbours: number[]
}

/**
 * Build a Goldberg polyhedron (truncated icosahedron-like) from the dual of a
 * subdivided icosahedron. `detail=1` gives the classic 42-cell soccer ball
 * (12 pentagons + 30 hexagons). `detail=2` gives 162 cells.
 */
export function buildGoldbergCells(radius: number, detail: 1 | 2 = 1): GoldbergCell[] {
  const ico = new THREE.IcosahedronGeometry(radius, detail)
  const merged = mergeVerticesFallback(ico)
  const mergedPos = merged.attributes.position as THREE.BufferAttribute
  const mergedIdx = merged.index as THREE.BufferAttribute

  const vCount = mergedPos.count
  const fCount = mergedIdx.count / 3

  const vertexFaces: number[][] = Array.from({ length: vCount }, () => [])
  const faceCentroids: THREE.Vector3[] = []
  for (let f = 0; f < fCount; f++) {
    const a = mergedIdx.getX(f * 3)
    const b = mergedIdx.getX(f * 3 + 1)
    const c = mergedIdx.getX(f * 3 + 2)
    vertexFaces[a].push(f)
    vertexFaces[b].push(f)
    vertexFaces[c].push(f)
    const va = new THREE.Vector3().fromBufferAttribute(mergedPos, a)
    const vb = new THREE.Vector3().fromBufferAttribute(mergedPos, b)
    const vc = new THREE.Vector3().fromBufferAttribute(mergedPos, c)
    faceCentroids[f] = va.add(vb).add(vc).divideScalar(3).setLength(radius)
  }

  const cells: GoldbergCell[] = []
  for (let v = 0; v < vCount; v++) {
    const faces = vertexFaces[v]
    const centre = new THREE.Vector3().fromBufferAttribute(mergedPos, v).setLength(radius)

    const normal = centre.clone().normalize()
    const tangent = new THREE.Vector3()
    if (Math.abs(normal.y) < 0.99) {
      tangent.copy(new THREE.Vector3(0, 1, 0)).cross(normal).normalize()
    } else {
      tangent.copy(new THREE.Vector3(1, 0, 0)).cross(normal).normalize()
    }
    const bitangent = new THREE.Vector3().copy(normal).cross(tangent).normalize()

    const withAngle = faces.map((f) => {
      const d = faceCentroids[f].clone().sub(centre)
      const x = d.dot(tangent)
      const y = d.dot(bitangent)
      return { face: f, angle: Math.atan2(y, x) }
    })
    withAngle.sort((a, b) => a.angle - b.angle)
    const sortedFaces = withAngle.map((w) => w.face)

    const sides = (sortedFaces.length === 5 ? 5 : 6) as 5 | 6
    const verts = sortedFaces.map((f) => faceCentroids[f].toArray() as [number, number, number])
    cells.push({
      index: v,
      sides,
      centroid: centre.toArray() as [number, number, number],
      vertices: verts,
      neighbours: [],
    })
  }

  const vertKey = (v: [number, number, number]) =>
    `${v[0].toFixed(4)}|${v[1].toFixed(4)}|${v[2].toFixed(4)}`
  const edgeToCells = new Map<string, number[]>()
  for (const cell of cells) {
    const n = cell.vertices.length
    for (let i = 0; i < n; i++) {
      const a = vertKey(cell.vertices[i])
      const b = vertKey(cell.vertices[(i + 1) % n])
      const key = a < b ? `${a}__${b}` : `${b}__${a}`
      const arr = edgeToCells.get(key) ?? []
      arr.push(cell.index)
      edgeToCells.set(key, arr)
    }
  }
  for (const pair of edgeToCells.values()) {
    if (pair.length !== 2) continue
    const [a, b] = pair
    if (!cells[a].neighbours.includes(b)) cells[a].neighbours.push(b)
    if (!cells[b].neighbours.includes(a)) cells[b].neighbours.push(a)
  }

  return cells
}

function mergeVerticesFallback(geo: THREE.BufferGeometry): THREE.BufferGeometry {
  const pos = geo.attributes.position as THREE.BufferAttribute
  const uniq: number[] = []
  const remap: number[] = []
  const key = (x: number, y: number, z: number) =>
    `${x.toFixed(5)}|${y.toFixed(5)}|${z.toFixed(5)}`
  const map = new Map<string, number>()
  for (let i = 0; i < pos.count; i++) {
    const x = pos.getX(i), y = pos.getY(i), z = pos.getZ(i)
    const k = key(x, y, z)
    let idx = map.get(k)
    if (idx === undefined) {
      idx = uniq.length / 3
      uniq.push(x, y, z)
      map.set(k, idx)
    }
    remap.push(idx)
  }
  const out = new THREE.BufferGeometry()
  out.setAttribute('position', new THREE.Float32BufferAttribute(uniq, 3))
  out.setIndex(remap)
  return out
}

/**
 * Deterministically assign cells to wings via BFS seeded at Fibonacci-sphere
 * anchors. Returns a map cellIndex → wingName (null for empty cells).
 */
export function assignWings(
  cells: GoldbergCell[],
  wings: { name: string; drawerCount: number }[],
): Map<number, string | null> {
  const result = new Map<number, string | null>()
  for (const c of cells) result.set(c.index, null)
  if (wings.length === 0) return result

  const sortedWings = [...wings].sort((a, b) => b.drawerCount - a.drawerCount)

  const perWingRaw = Math.floor(cells.length / sortedWings.length)
  const perWing = Math.max(2, Math.min(14, perWingRaw))

  const golden = Math.PI * (3 - Math.sqrt(5))
  const claimed = new Set<number>()
  const seedFor = (i: number): number => {
    const y = 1 - (i + 0.5) * (2 / sortedWings.length)
    const r = Math.sqrt(Math.max(0, 1 - y * y))
    const theta = golden * i
    const sx = r * Math.cos(theta), sy = y, sz = r * Math.sin(theta)
    let best = 0, bestD = Infinity
    for (const c of cells) {
      if (claimed.has(c.index)) continue
      const [cx, cy, cz] = c.centroid
      const len = Math.hypot(cx, cy, cz) || 1
      const nx = cx / len, ny = cy / len, nz = cz / len
      const d = (nx - sx) ** 2 + (ny - sy) ** 2 + (nz - sz) ** 2
      if (d < bestD) { bestD = d; best = c.index }
    }
    return best
  }

  for (let i = 0; i < sortedWings.length; i++) {
    const wing = sortedWings[i]
    const seed = seedFor(i)
    const frontier: number[] = [seed]
    let remaining = perWing
    while (remaining > 0 && frontier.length > 0) {
      const current = frontier.shift()!
      if (claimed.has(current)) continue
      claimed.add(current)
      result.set(current, wing.name)
      remaining--
      if (remaining === 0) break
      for (const nb of cells[current].neighbours) {
        if (!claimed.has(nb) && !frontier.includes(nb)) frontier.push(nb)
      }
    }
  }

  return result
}
