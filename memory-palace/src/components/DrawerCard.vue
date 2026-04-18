<script setup lang="ts">
import { onBeforeUnmount, shallowRef, watch } from 'vue'
import * as THREE from 'three'
import type { Drawer, Fact, Tunnel } from '../types/palace'
import { getCardPaperCanvas } from '../composables/useTextures'

export type CardType = 'cover' | 'summary' | 'keyPoints' | 'insight' | 'content' | 'connections'

export interface TunnelHotspot {
  targetId: string
  y: number
}

const props = defineProps<{
  drawer: Drawer
  type: CardType
  accent: string
  position: [number, number, number]
  rotationX?: number
  visible?: boolean
  isTop?: boolean
  targetResolver: (targetId: string) => string
}>()

const emit = defineEmits<{
  (e: 'topClick'): void
  (e: 'directClick'): void
  (e: 'tunnelClick', targetId: string): void
}>()

const CARD_W = 768
const CARD_H = 1086
const W_WORLD = 1.2
const H_WORLD = 1.7

const texture = shallowRef<THREE.CanvasTexture | null>(null)
const hotspots = shallowRef<TunnelHotspot[]>([])

function wrapText(ctx: CanvasRenderingContext2D, text: string, maxWidth: number): string[] {
  const words = text.split(/\s+/)
  const lines: string[] = []
  let current = ''
  for (const w of words) {
    const tryLine = current ? current + ' ' + w : w
    if (ctx.measureText(tryLine).width > maxWidth && current) {
      lines.push(current); current = w
    } else {
      current = tryLine
    }
  }
  if (current) lines.push(current)
  return lines
}

function drawHeader(ctx: CanvasRenderingContext2D, text: string) {
  ctx.font = 'bold 32px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#0a0a1a'
  ctx.textAlign = 'left'
  ctx.textBaseline = 'top'
  ctx.fillText(text, 40, 110)
}

function buildCover(canvas: HTMLCanvasElement) {
  const ctx = canvas.getContext('2d')!
  drawHeader(ctx, 'COVER')

  ctx.font = 'bold 52px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#0a0a1a'
  const titleLines = wrapText(ctx, props.drawer.title, CARD_W - 80)
  let y = 200
  for (const line of titleLines.slice(0, 3)) {
    ctx.fillText(line, 40, y); y += 62
  }

  y += 20
  ctx.font = '24px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#555566'
  ctx.fillText(`${props.drawer.wing}  /  ${props.drawer.hall}  /  ${props.drawer.room}`, 40, y)
  y += 50

  const chipColor = props.drawer.status === 'pending' ? '#FF8C00' : '#00AA66'
  ctx.fillStyle = chipColor
  ctx.beginPath(); ctx.roundRect(40, y, 180, 36, 6); ctx.fill()
  ctx.font = 'bold 20px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#fff'
  ctx.textAlign = 'center'
  ctx.fillText(props.drawer.status.toUpperCase(), 130, y + 25)
  ctx.textAlign = 'left'

  y += 68
  ctx.font = '22px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#555566'
  ctx.fillText(`Since ${props.drawer.validFrom.slice(0, 10)}`, 40, y)
}

function buildTextCard(canvas: HTMLCanvasElement, heading: string, body: string, italic = false) {
  const ctx = canvas.getContext('2d')!
  drawHeader(ctx, heading)
  ctx.font = (italic ? 'italic ' : '') + '24px "Georgia", serif'
  ctx.fillStyle = '#1a1a2e'
  const lines = wrapText(ctx, body || `(no ${heading.toLowerCase()})`, CARD_W - 80)
  let y = 200
  const lineH = 34
  for (const line of lines) {
    if (y > CARD_H - 60) { ctx.fillText('…', 40, y); break }
    ctx.fillText(line, 40, y); y += lineH
  }
}

function buildKeyPoints(canvas: HTMLCanvasElement) {
  const ctx = canvas.getContext('2d')!
  drawHeader(ctx, 'KEY POINTS')
  ctx.font = '24px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#1a1a2e'
  let y = 200
  const pts = props.drawer.keyPoints
  if (pts.length === 0) {
    ctx.fillText('(none)', 40, y); return
  }
  for (const p of pts) {
    const lines = wrapText(ctx, '• ' + p, CARD_W - 80)
    for (const ln of lines) {
      if (y > CARD_H - 60) return
      ctx.fillText(ln, 40, y); y += 32
    }
    y += 6
  }
}

function buildContent(canvas: HTMLCanvasElement) {
  const ctx = canvas.getContext('2d')!
  drawHeader(ctx, 'CONTENT')
  ctx.font = '18px "Courier New", Courier, monospace'
  ctx.fillStyle = '#1a1a2e'
  const lines = wrapText(ctx, props.drawer.content, CARD_W - 80)
  let y = 200
  for (const line of lines) {
    if (y > CARD_H - 60) { ctx.fillText('…', 40, y); break }
    ctx.fillText(line, 40, y); y += 26
  }
}

function buildConnections(canvas: HTMLCanvasElement): TunnelHotspot[] {
  const ctx = canvas.getContext('2d')!
  drawHeader(ctx, 'CONNECTIONS')
  const spots: TunnelHotspot[] = []

  let y = 200
  ctx.font = 'bold 22px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#0a0a1a'
  ctx.fillText('Tunnels', 40, y); y += 36

  const tunnels: Tunnel[] = props.drawer.tunnels
  if (tunnels.length === 0) {
    ctx.font = '22px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = '#555566'
    ctx.fillText('(none)', 56, y); y += 32
  } else {
    ctx.font = '22px "Segoe UI", Arial, sans-serif'
    for (const t of tunnels) {
      const title = props.targetResolver(t.targetId)
      const text = `→ ${t.relation}  ${title}`
      ctx.fillStyle = '#0066aa'
      ctx.fillText(text, 56, y)
      const centreCanvasY = y + 11
      const worldY = H_WORLD * (0.5 - centreCanvasY / CARD_H)
      spots.push({ targetId: t.targetId, y: worldY })
      y += 36
    }
  }

  y += 14
  ctx.font = 'bold 22px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#0a0a1a'
  ctx.fillText('Facts', 40, y); y += 36

  const facts: Fact[] = props.drawer.facts
  if (facts.length === 0) {
    ctx.font = '22px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = '#555566'
    ctx.fillText('(none)', 56, y)
  } else {
    ctx.font = '20px "Segoe UI", Arial, sans-serif'
    for (const f of facts) {
      if (y > CARD_H - 80) { ctx.fillText('…', 56, y); break }
      ctx.fillStyle = '#1a1a2e'
      const line = `${f.subject}  —  ${f.predicate}  →  ${f.object}`
      const wrapped = wrapText(ctx, line, CARD_W - 120)
      for (const ln of wrapped) {
        if (y > CARD_H - 80) break
        ctx.fillText(ln, 56, y); y += 28
      }
      y += 8
    }
  }

  return spots
}

function rebuild() {
  const canvas = getCardPaperCanvas(props.accent)
  let newHotspots: TunnelHotspot[] = []
  switch (props.type) {
    case 'cover':        buildCover(canvas); break
    case 'summary':      buildTextCard(canvas, 'SUMMARY', props.drawer.summary); break
    case 'keyPoints':    buildKeyPoints(canvas); break
    case 'insight':      buildTextCard(canvas, 'INSIGHT', props.drawer.insight, true); break
    case 'content':      buildContent(canvas); break
    case 'connections':  newHotspots = buildConnections(canvas); break
  }
  if (texture.value) texture.value.dispose()
  const tex = new THREE.CanvasTexture(canvas)
  tex.needsUpdate = true
  texture.value = tex
  hotspots.value = newHotspots
}

watch(() => [props.drawer.id, props.type, props.accent], rebuild, { immediate: true })

onBeforeUnmount(() => {
  if (texture.value) texture.value.dispose()
})

function onMainClick(e: any) {
  e.stopPropagation?.()
  if (props.isTop) emit('topClick')
  else emit('directClick')
}

function onHotspotClick(e: any, targetId: string) {
  e.stopPropagation?.()
  emit('tunnelClick', targetId)
}

const HOTSPOT_W = W_WORLD * 0.9
const HOTSPOT_H = 0.06
</script>

<template>
  <TresGroup v-if="visible !== false" :position="position" :rotation-x="rotationX ?? 0">
    <TresMesh :position-z="-0.003">
      <TresPlaneGeometry :args="[W_WORLD * 1.03, H_WORLD * 1.02]" />
      <TresMeshBasicMaterial :color="accent" :transparent="true" :opacity="0.22" />
    </TresMesh>
    <TresMesh @click="onMainClick">
      <TresPlaneGeometry :args="[W_WORLD, H_WORLD]" />
      <TresMeshBasicMaterial :map="texture" :transparent="true" />
    </TresMesh>
    <TresMesh
      v-for="hs in hotspots"
      :key="hs.targetId"
      :position="[0, hs.y, 0.001]"
      @click="(e: any) => onHotspotClick(e, hs.targetId)"
    >
      <TresPlaneGeometry :args="[HOTSPOT_W, HOTSPOT_H]" />
      <TresMeshBasicMaterial :transparent="true" :opacity="0" />
    </TresMesh>
  </TresGroup>
</template>
