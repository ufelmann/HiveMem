<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Application, Container, Sprite, Graphics } from 'pixi.js'
import { wingTexture, drawerTexture, colorForWing, parseHsl } from './textures'
import { useCanvasStore } from '../../stores/canvas'
import { computeWingPositions, poissonDiskDrawers } from '../../composables/layout'
import type { Drawer } from '../../api/types'

const root = ref<HTMLDivElement>()
const canvasStore = useCanvasStore()
let app: Application | null = null
let world: Container | null = null

onMounted(async () => {
  if (!root.value) return
  app = new Application()
  await app.init({ background: 0x050510, resizeTo: root.value, antialias: true, resolution: devicePixelRatio, autoDensity: true })
  root.value.appendChild(app.canvas)
  world = new Container(); app.stage.addChild(world)
  render()
})

onBeforeUnmount(() => { app?.destroy(true, { children: true, texture: false }); app = null; world = null })

function render() {
  if (!world || !app) return
  world.removeChildren()
  const width = app.screen.width, height = app.screen.height

  // 1. Group drawers by wing
  const drawersByWing = new Map<string, Drawer[]>()
  for (const d of canvasStore.drawers) {
    if (!drawersByWing.has(d.wing)) drawersByWing.set(d.wing, [])
    drawersByWing.get(d.wing)!.push(d)
  }

  // 2. Compute positions
  const relationColor: Record<string, number> = {
    related_to: 0x5a5a5a,
    builds_on: 0x4dc4ff,
    contradicts: 0xff4d4d,
    refines: 0x4dff9c
  }

  const wingPos = computeWingPositions(canvasStore.wings, canvasStore.drawers, canvasStore.tunnels, { width, height })
  const drawerPos = new Map<string, { x: number; y: number }>()
  for (const w of canvasStore.wings) {
    const p = wingPos.get(w.name)
    if (!p) continue
    const group = drawersByWing.get(w.name) ?? []
    const pts = poissonDiskDrawers(group.length, { x: p.x, y: p.y, r: 70, minDist: 14, seed: w.name })
    group.forEach((d, i) => drawerPos.set(d.id, pts[i]))
  }

  // 3. Draw edges under spheres
  const edges = new Graphics()
  ;(edges as any)._kind = 'edges'
  for (const t of canvasStore.tunnels) {
    const a = drawerPos.get(t.from_drawer)
    const b = drawerPos.get(t.to_drawer)
    if (!a || !b) continue
    const col = relationColor[t.relation] ?? 0x666666
    const w = Math.max(0.6, Math.log(2) * 1.3)
    edges.moveTo(a.x, a.y).lineTo(b.x, b.y).stroke({ width: w, color: col, alpha: 0.4 })
  }
  world!.addChild(edges)

  // 4. Draw wings (on top of edges)
  canvasStore.wings.forEach(w => {
    const p = wingPos.get(w.name)
    if (!p) return
    const size = 120 + Math.log(1 + w.drawer_count) * 30
    const s: any = new Sprite(wingTexture(colorForWing(w.name)))
    s.anchor.set(0.5)
    s.width = s.height = size
    s.x = p.x
    s.y = p.y
    s._kind = 'wing'
    s._name = w.name
    world!.addChild(s)
  })

  // 5. Draw drawers (on top of everything)
  for (const [wingName, group] of drawersByWing) {
    group.forEach(d => {
      const pt = drawerPos.get(d.id)
      if (!pt) return
      const ds: any = new Sprite(drawerTexture())
      ds.anchor.set(0.5)
      ds.width = ds.height = 14 + d.importance * 4
      ds.tint = parseHsl(colorForWing(d.wing))
      ds.x = pt.x
      ds.y = pt.y
      ds._kind = 'drawer'
      ds._drawerId = d.id
      world!.addChild(ds)
    })
  }
}

watch(() => canvasStore.loaded, v => { if (v) render() })
watch(() => canvasStore.drawers.length, () => render())
</script>

<template><div ref="root" class="canvas-root" /></template>
<style scoped>.canvas-root { position:absolute; inset:0; }</style>
