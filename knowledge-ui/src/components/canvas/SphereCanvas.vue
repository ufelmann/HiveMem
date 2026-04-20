<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Application, Container, Sprite, Graphics } from 'pixi.js'
import { realmTexture, cellTexture, colorForRealm, parseHsl } from './textures'
import { focusFilter, hoverFilter, focusRing, godrays } from './filters'
import { spawnDust } from './particles'
import { useCanvasStore } from '../../stores/canvas'
import { useCellStore } from '../../stores/cell'
import { useReaderStore } from '../../stores/reader'
import { computeWingPositions, poissonDiskCells } from '../../composables/layout'
import { cellVisibleAt } from '../../composables/lod'
import type { Cell } from '../../api/types'

const root = ref<HTMLDivElement>()
const canvasStore = useCanvasStore()
let app: Application | null = null
let world: Container | null = null
let snapToRef: ((worldX: number, worldY: number, targetZoom: number, onDone: () => void) => void) | null = null
let onCellClickRef: ((c: Cell) => void) | null = null
let cleanup: (() => void) | null = null

onMounted(async () => {
  if (!root.value) return
  app = new Application()
  await app.init({ background: 0x050510, resizeTo: root.value, antialias: true, resolution: devicePixelRatio, autoDensity: true })
  root.value.appendChild(app.canvas)
  world = new Container(); app.stage.addChild(world)

  // Add dust emitter
  const dustLayer = new Container(); app.stage.addChild(dustLayer)
  const isMobile = window.matchMedia('(max-width: 768px)').matches
  const dustUpdate = spawnDust(dustLayer, isMobile ? 120 : 280, { w: app.screen.width, h: app.screen.height })
  app.ticker.add(t => dustUpdate(t.deltaMS))

  // Add godray background
  const bg = new Graphics().rect(0, 0, 4000, 4000).fill(0x05050f)
  ;(bg as any).filters = [godrays()]
  app.stage.addChildAt(bg, 0)

  let zoom = 1, panX = 0, panY = 0

  app.canvas.addEventListener('wheel', e => {
    e.preventDefault()
    const factor = Math.exp(-e.deltaY * 0.0015)
    const next = Math.min(6, Math.max(0.15, zoom * factor))
    const mouseX = e.offsetX, mouseY = e.offsetY
    panX = mouseX - (mouseX - panX) * (next / zoom)
    panY = mouseY - (mouseY - panY) * (next / zoom)
    zoom = next
    applyTransform()
  }, { passive: false })

  let dragging = false, dragStartX = 0, dragStartY = 0, dragStartPanX = 0, dragStartPanY = 0
  app.canvas.addEventListener('pointerdown', e => {
    dragging = true; dragStartX = e.clientX; dragStartY = e.clientY
    dragStartPanX = panX; dragStartPanY = panY
  })
  const onPointerUp = () => { dragging = false }
  const onPointerMove = (e: PointerEvent) => {
    if (!dragging) return
    panX = dragStartPanX + (e.clientX - dragStartX)
    panY = dragStartPanY + (e.clientY - dragStartY)
    applyTransform()
  }
  window.addEventListener('pointerup', onPointerUp)
  window.addEventListener('pointermove', onPointerMove)
  cleanup = () => {
    window.removeEventListener('pointerup', onPointerUp)
    window.removeEventListener('pointermove', onPointerMove)
  }

  function applyTransform() {
    if (!world || !app) return
    world.scale.set(zoom); world.position.set(panX, panY)
    const viewLeft = -panX / zoom, viewTop = -panY / zoom
    const viewRight = viewLeft + app.screen.width / zoom
    const viewBottom = viewTop + app.screen.height / zoom
    for (const c of world.children) {
      const s = c as any
      if (s._kind === 'cell') {
        let vis = cellVisibleAt(zoom)
        if (vis) {
          const r = Math.max(s.width, s.height)
          vis = s.x + r > viewLeft && s.x - r < viewRight
             && s.y + r > viewTop  && s.y - r < viewBottom
        }
        s.visible = vis
      }
    }
  }

  function snapTo(worldX: number, worldY: number, targetZoom: number, onDone: () => void) {
    if (!app) return
    const startZoom = zoom, startPanX = panX, startPanY = panY
    const targetPanX = app.screen.width / 2 - worldX * targetZoom
    const targetPanY = app.screen.height / 2 - worldY * targetZoom
    const startT = performance.now()
    function tick(t: number) {
      const k = Math.min(1, (t - startT) / 280)
      const e = k * k * (3 - 2 * k)
      zoom = startZoom + (targetZoom - startZoom) * e
      panX = startPanX + (targetPanX - startPanX) * e
      panY = startPanY + (targetPanY - startPanY) * e
      applyTransform()
      if (k < 1) requestAnimationFrame(tick); else onDone()
    }
    requestAnimationFrame(tick)
  }

  function onCellClick(c: Cell) {
    useCellStore().load(c.id)
    canvasStore.setFocus(c.id)
  }

  snapToRef = snapTo
  onCellClickRef = onCellClick
  render()
})

onBeforeUnmount(() => {
  cleanup?.(); cleanup = null
  app?.destroy(true, { children: true, texture: false }); app = null; world = null
})

function render() {
  if (!world || !app) return
  world.removeChildren()
  const width = app.screen.width, height = app.screen.height

  // 1. Group cells by realm
  const cellsByRealm = new Map<string, Cell[]>()
  for (const c of canvasStore.cells) {
    if (!cellsByRealm.has(c.realm)) cellsByRealm.set(c.realm, [])
    cellsByRealm.get(c.realm)!.push(c)
  }

  // 2. Compute positions
  const relationColor: Record<string, number> = {
    related_to: 0x5a5a5a,
    builds_on: 0x4dc4ff,
    contradicts: 0xff4d4d,
    refines: 0x4dff9c
  }

  const realmPos = computeWingPositions(canvasStore.realms, canvasStore.cells, canvasStore.tunnels, { width, height })
  const cellPos = new Map<string, { x: number; y: number }>()
  for (const r of canvasStore.realms) {
    const p = realmPos.get(r.name)
    if (!p) continue
    const group = cellsByRealm.get(r.name) ?? []
    const pts = poissonDiskCells(group.length, { x: p.x, y: p.y, r: 70, minDist: 14, seed: r.name })
    group.forEach((c, i) => cellPos.set(c.id, pts[i]))
  }

  // 3. Draw edges under spheres
  const edges = new Graphics()
  ;(edges as any)._kind = 'edges'
  for (const t of canvasStore.tunnels) {
    const a = cellPos.get(t.from_cell)
    const b = cellPos.get(t.to_cell)
    if (!a || !b) continue
    const col = relationColor[t.relation] ?? 0x666666
    const w = Math.max(0.6, Math.log(2) * 1.3)
    edges.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: w, color: col, alpha: 0.4 })
  }
  world!.addChild(edges)

  // 4. Draw realms (on top of edges)
  canvasStore.realms.forEach(r => {
    const p = realmPos.get(r.name)
    if (!p) return
    const size = 120 + Math.log(1 + r.cell_count) * 30
    const s: any = new Sprite(realmTexture(colorForRealm(r.name)))
    s.anchor.set(0.5)
    s.width = s.height = size
    s.x = p.x
    s.y = p.y
    s._kind = 'realm'
    s._name = r.name
    world!.addChild(s)
  })

  // 5. Draw cells (on top of everything)
  for (const [_realmName, group] of cellsByRealm) {
    group.forEach(c => {
      const pt = cellPos.get(c.id)
      if (!pt) return
      const ds: any = new Sprite(cellTexture())
      ds.anchor.set(0.5)
      ds.width = ds.height = 14 + c.importance * 4
      ds.tint = parseHsl(colorForRealm(c.realm))
      ds.x = pt.x
      ds.y = pt.y
      ds._kind = 'cell'
      ds._cellId = c.id
      ds.eventMode = 'static'
      ds.cursor = 'pointer'
      let lastClick = 0
      ds.on('pointertap', () => {
        const now = performance.now()
        if (now - lastClick < 320) {
          useReaderStore().openReader(c.id)
        } else {
          snapToRef?.(pt.x, pt.y, 2.2, () => onCellClickRef?.(c))
        }
        lastClick = now
      })
      ds.on('pointerover', () => {
        ds.filters = [hoverFilter()]
      })
      ds.on('pointerout', () => {
        ds.filters = canvasStore.focusedId === c.id ? [focusFilter(), focusRing()] : []
      })
      world!.addChild(ds)
    })
  }
}

watch(() => canvasStore.loaded, v => { if (v) render() })
watch(() => canvasStore.cells.length, () => render())

watch(() => canvasStore.focusedId, id => {
  if (!world) return
  for (const c of world.children) {
    const s = c as any
    if (s._kind !== 'cell') continue
    s.filters = s._cellId === id ? [focusFilter(), focusRing()] : []
  }
})
</script>

<template><div ref="root" class="canvas-root" /></template>
<style scoped>.canvas-root { position:absolute; inset:0; }</style>
