<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Application, Container, Sprite } from 'pixi.js'
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
  const drawersByWing = new Map<string, Drawer[]>()
  for (const d of canvasStore.drawers) {
    if (!drawersByWing.has(d.wing)) drawersByWing.set(d.wing, [])
    drawersByWing.get(d.wing)!.push(d)
  }
  const wingPos = computeWingPositions(canvasStore.wings, canvasStore.drawers, canvasStore.tunnels, { width, height })
  canvasStore.wings.forEach(w => {
    const p = wingPos.get(w.name); if (!p) return
    const size = 120 + Math.log(1 + w.drawer_count) * 30
    const s: any = new Sprite(wingTexture(colorForWing(w.name)))
    s.anchor.set(0.5); s.width = s.height = size; s.x = p.x; s.y = p.y
    s._kind = 'wing'; s._name = w.name
    world!.addChild(s)
    const group = drawersByWing.get(w.name) ?? []
    const pts = poissonDiskDrawers(group.length, { x: p.x, y: p.y, r: 70, minDist: 14, seed: w.name })
    group.forEach((d, i) => {
      const pt = pts[i]
      const ds: any = new Sprite(drawerTexture())
      ds.anchor.set(0.5); ds.width = ds.height = 14 + d.importance * 4
      ds.tint = parseHsl(colorForWing(d.wing))
      ds.x = pt.x; ds.y = pt.y
      ds._kind = 'drawer'; ds._drawerId = d.id
      world!.addChild(ds)
    })
  })
}

watch(() => canvasStore.loaded, v => { if (v) render() })
watch(() => canvasStore.drawers.length, () => render())
</script>

<template><div ref="root" class="canvas-root" /></template>
<style scoped>.canvas-root { position:absolute; inset:0; }</style>
