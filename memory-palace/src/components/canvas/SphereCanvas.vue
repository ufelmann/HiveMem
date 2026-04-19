<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { Application, Container, Sprite } from 'pixi.js'
import { wingTexture, drawerTexture, colorForWing, parseHsl } from './textures'
import { useCanvasStore } from '../../stores/canvas'
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
  const cx = app.screen.width / 2
  const cy = app.screen.height / 2
  const R = Math.min(cx, cy) * 0.5
  const drawersByWing = new Map<string, Drawer[]>()
  for (const d of canvasStore.drawers) {
    if (!drawersByWing.has(d.wing)) drawersByWing.set(d.wing, [])
    drawersByWing.get(d.wing)!.push(d)
  }
  canvasStore.wings.forEach((w, i) => {
    const angle = (i / canvasStore.wings.length) * Math.PI * 2
    const wx = cx + Math.cos(angle) * R
    const wy = cy + Math.sin(angle) * R
    const size = 120 + Math.log(1 + w.drawer_count) * 30
    const s: any = new Sprite(wingTexture(colorForWing(w.name)))
    s.anchor.set(0.5); s.width = s.height = size; s.x = wx; s.y = wy; s._kind = 'wing'; s._name = w.name
    world!.addChild(s)
    const group = drawersByWing.get(w.name) ?? []
    group.forEach((d) => {
      const a = hashAngle(d.id)
      const rr = 40 + (hashRadius(d.id) % 60)
      const ds: any = new Sprite(drawerTexture())
      ds.anchor.set(0.5); ds.width = ds.height = 14 + d.importance * 4
      ds.tint = parseHsl(colorForWing(d.wing))
      ds.x = wx + Math.cos(a) * rr; ds.y = wy + Math.sin(a) * rr
      ds._kind = 'drawer'; ds._drawerId = d.id
      world!.addChild(ds)
    })
  })
}

watch(() => canvasStore.loaded, v => { if (v) render() })
watch(() => canvasStore.drawers.length, () => render())

function hashAngle(id: string) { let h = 0; for (const c of id) h = (h * 17 + c.charCodeAt(0)) % 1000; return (h / 1000) * Math.PI * 2 }
function hashRadius(id: string) { let h = 0; for (const c of id) h = (h * 29 + c.charCodeAt(0)) % 10000; return h }
</script>

<template><div ref="root" class="canvas-root" /></template>
<style scoped>.canvas-root { position:absolute; inset:0; }</style>
