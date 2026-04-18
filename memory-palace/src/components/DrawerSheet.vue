<script setup lang="ts">
import { watch, onBeforeUnmount, shallowRef } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'

const store = useNavigationStore()

// ────────────────────────────────────────────────────────────────────────────
// Canvas constants
// ────────────────────────────────────────────────────────────────────────────
const CW = 768
const CH = 1075

const COLOR_BG       = '#f4efe5'
const COLOR_HEADER   = '#0a0a1a'
const COLOR_TITLE    = '#00BFFF'
const COLOR_META     = '#555566'
const COLOR_BODY     = '#1a1a2e'
const COLOR_DIVIDER  = 'rgba(0,191,255,0.4)'
const COLOR_SECTION  = '#00BFFF'
const COLOR_TUNNEL   = '#0066aa'

// ────────────────────────────────────────────────────────────────────────────
// Text-wrap helper
// ────────────────────────────────────────────────────────────────────────────
function wrapText(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
): string[] {
  const words = text.split(/\s+/)
  const lines: string[] = []
  let current = ''
  for (const w of words) {
    const tryLine = current ? current + ' ' + w : w
    if (ctx.measureText(tryLine).width > maxWidth && current) {
      lines.push(current)
      current = w
    } else {
      current = tryLine
    }
  }
  if (current) lines.push(current)
  return lines
}

function drawDivider(ctx: CanvasRenderingContext2D, y: number) {
  ctx.strokeStyle = COLOR_DIVIDER
  ctx.lineWidth = 1.5
  ctx.beginPath()
  ctx.moveTo(28, y)
  ctx.lineTo(CW - 28, y)
  ctx.stroke()
}

function drawBorderGlow(ctx: CanvasRenderingContext2D) {
  // Left edge
  const gL = ctx.createLinearGradient(0, 0, 14, 0)
  gL.addColorStop(0, 'rgba(0,191,255,0.55)'); gL.addColorStop(1, 'rgba(0,191,255,0)')
  ctx.fillStyle = gL; ctx.fillRect(0, 0, 14, CH)
  // Right edge
  const gR = ctx.createLinearGradient(CW - 14, 0, CW, 0)
  gR.addColorStop(0, 'rgba(0,191,255,0)'); gR.addColorStop(1, 'rgba(0,191,255,0.55)')
  ctx.fillStyle = gR; ctx.fillRect(CW - 14, 0, 14, CH)
  // Top edge
  const gT = ctx.createLinearGradient(0, 0, 0, 14)
  gT.addColorStop(0, 'rgba(0,191,255,0.55)'); gT.addColorStop(1, 'rgba(0,191,255,0)')
  ctx.fillStyle = gT; ctx.fillRect(0, 0, CW, 14)
  // Bottom edge
  const gB = ctx.createLinearGradient(0, CH - 14, 0, CH)
  gB.addColorStop(0, 'rgba(0,191,255,0)'); gB.addColorStop(1, 'rgba(0,191,255,0.55)')
  ctx.fillStyle = gB; ctx.fillRect(0, CH - 14, CW, 14)
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet A: meta + summary + insight
// ────────────────────────────────────────────────────────────────────────────
function buildMetaCanvas(): HTMLCanvasElement {
  const drawer = store.selectedDrawer!
  const canvas = document.createElement('canvas')
  canvas.width = CW
  canvas.height = CH
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)
  drawBorderGlow(ctx)

  // Header strip
  const HEADER_H = Math.round(CH * 0.14)
  const headerGrad = ctx.createLinearGradient(0, 0, 0, HEADER_H)
  headerGrad.addColorStop(0, COLOR_HEADER)
  headerGrad.addColorStop(1, '#0d2030')
  ctx.fillStyle = headerGrad
  ctx.fillRect(0, 0, CW, HEADER_H)

  ctx.font = 'bold 44px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_TITLE
  ctx.textBaseline = 'middle'
  const maxTitleW = CW - 60
  const titleLines = wrapText(ctx, drawer.title, maxTitleW)
  const lineH = 52
  const titleBlockH = titleLines.length * lineH
  const titleY = (HEADER_H - titleBlockH) / 2 + lineH / 2
  titleLines.forEach((line, i) => {
    ctx.fillText(line, 30, titleY + i * lineH)
  })

  let cursor = HEADER_H + 22

  // Wing / hall / room
  ctx.font = '24px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_META
  ctx.textBaseline = 'top'
  ctx.fillText(`${drawer.wing}  /  ${drawer.hall}  /  ${drawer.room}`, 30, cursor)

  // Status chip
  const statusLabel = drawer.status === 'pending' ? 'PENDING' : 'COMMITTED'
  const statusColor = drawer.status === 'pending' ? '#FF8C00' : '#00AA66'
  const chipW = 140
  const chipH = 30
  const chipX = CW - 30 - chipW
  ctx.fillStyle = statusColor
  ctx.beginPath()
  ctx.roundRect(chipX, cursor - 2, chipW, chipH, 5)
  ctx.fill()
  ctx.font = 'bold 18px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#fff'
  ctx.textAlign = 'center'
  ctx.fillText(statusLabel, chipX + chipW / 2, cursor + 4)
  ctx.textAlign = 'left'

  cursor += 44
  drawDivider(ctx, cursor)
  cursor += 20

  const MARGIN = 30
  const TEXT_W = CW - MARGIN * 2

  // L1 Summary section
  if (drawer.summary) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Summary', MARGIN, cursor)
    cursor += 36

    ctx.font = '22px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_BODY
    const sumLines = wrapText(ctx, drawer.summary, TEXT_W)
    for (const line of sumLines) {
      if (cursor > CH * 0.65) break
      ctx.fillText(line, MARGIN, cursor)
      cursor += 30
    }
    cursor += 12
    drawDivider(ctx, cursor)
    cursor += 20
  }

  // Insight section
  if (drawer.insight) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Insight', MARGIN, cursor)
    cursor += 36

    ctx.font = 'italic 22px "Georgia", serif'
    ctx.fillStyle = COLOR_BODY
    const insLines = wrapText(ctx, drawer.insight, TEXT_W - 30)
    for (const line of insLines) {
      if (cursor > CH * 0.9) break
      ctx.fillText(line, MARGIN + 16, cursor)
      cursor += 30
    }
  }

  return canvas
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet B: full L0 content
// ────────────────────────────────────────────────────────────────────────────
function buildContentCanvas(): HTMLCanvasElement {
  const drawer = store.selectedDrawer!
  const canvas = document.createElement('canvas')
  canvas.width = CW
  canvas.height = CH
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)
  drawBorderGlow(ctx)

  const MARGIN = 30
  const TEXT_W = CW - MARGIN * 2

  // Small "Content" heading at top
  ctx.font = 'bold 24px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_SECTION
  ctx.textBaseline = 'top'
  ctx.fillText('Content', MARGIN, 20)
  drawDivider(ctx, 52)

  let cursor = 68

  // Full content, smaller font to fit more
  ctx.font = '18px "Courier New", Courier, monospace'
  ctx.fillStyle = COLOR_BODY
  ctx.textBaseline = 'top'
  const BODY_LINE_H = 26
  const contentLines = wrapText(ctx, drawer.content, TEXT_W)
  for (const line of contentLines) {
    if (cursor + BODY_LINE_H > CH - 20) {
      ctx.fillStyle = COLOR_META
      ctx.font = '18px "Segoe UI", Arial, sans-serif'
      ctx.fillText('…', MARGIN, cursor)
      break
    }
    ctx.fillText(line, MARGIN, cursor)
    cursor += BODY_LINE_H
  }

  return canvas
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet C: key points + tunnels
// ────────────────────────────────────────────────────────────────────────────
interface TunnelHotspot {
  targetId: string
  canvasY: number  // canvas-space Y of row centre
}

function buildGraphCanvas(): { canvas: HTMLCanvasElement; hotspots: TunnelHotspot[] } {
  const drawer = store.selectedDrawer!
  const canvas = document.createElement('canvas')
  canvas.width = CW
  canvas.height = CH
  const ctx = canvas.getContext('2d')!

  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)
  drawBorderGlow(ctx)

  const MARGIN = 30
  const TEXT_W = CW - MARGIN * 2
  let cursor = 20

  // Key Points
  if (drawer.keyPoints.length > 0) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Key Points', MARGIN, cursor)
    cursor += 36
    drawDivider(ctx, cursor)
    cursor += 14

    ctx.font = '20px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_BODY
    for (const kp of drawer.keyPoints) {
      if (cursor > CH * 0.55) break
      const kpLines = wrapText(ctx, `• ${kp}`, TEXT_W - 16)
      for (const line of kpLines) {
        ctx.fillText(line, MARGIN + 8, cursor)
        cursor += 28
      }
      cursor += 4
    }
    cursor += 16
    drawDivider(ctx, cursor)
    cursor += 16
  }

  // Tunnels
  const hotspots: TunnelHotspot[] = []
  if (drawer.tunnels.length > 0) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Tunnels', MARGIN, cursor)
    cursor += 36

    ctx.font = '20px "Segoe UI", Arial, sans-serif'
    for (const tunnel of drawer.tunnels) {
      if (cursor > CH - 80) break
      const targetTitle = store.drawersById[tunnel.targetId]?.title ?? tunnel.targetId

      const rowH = 36
      const rowY = cursor - 4

      // Subtle row bg
      ctx.fillStyle = 'rgba(0,191,255,0.06)'
      ctx.fillRect(MARGIN, rowY, TEXT_W, rowH)

      ctx.fillStyle = COLOR_TUNNEL
      const label = `→ ${tunnel.relation}  ${targetTitle}`
      ctx.fillText(label, MARGIN, cursor)

      hotspots.push({ targetId: tunnel.targetId, canvasY: cursor + rowH / 2 })
      cursor += rowH + 4
    }
    cursor += 16
    drawDivider(ctx, cursor)
    cursor += 16
  }

  // Close hint at very bottom
  ctx.font = 'italic 18px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = 'rgba(0,0,0,0.35)'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'bottom'
  ctx.fillText('click to close', CW / 2, CH - 16)
  ctx.textAlign = 'left'

  return { canvas, hotspots }
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet geometry + materials — static
// ────────────────────────────────────────────────────────────────────────────
const SHEET_W = 1.6
const SHEET_H = 2.24

const planeGeo = new THREE.PlaneGeometry(SHEET_W, SHEET_H)
const rimGeo   = new THREE.PlaneGeometry(SHEET_W + 0.06, SHEET_H + 0.06)
const edgesGeo = new THREE.EdgesGeometry(new THREE.PlaneGeometry(SHEET_W + 0.04, SHEET_H + 0.04))

const rimMat = new THREE.MeshBasicMaterial({
  color: 0x00bfff,
  transparent: true,
  opacity: 0.18,
  side: THREE.FrontSide,
  depthWrite: false,
})

const lineMat = new THREE.LineBasicMaterial({
  color: 0x00bfff,
  transparent: true,
  opacity: 0.7,
})

const hotspotGeo = new THREE.PlaneGeometry(SHEET_W - 0.1, 0.14)
const hotspotMat = new THREE.MeshBasicMaterial({
  transparent: true,
  opacity: 0,
  side: THREE.DoubleSide,
  depthWrite: false,
})

// ────────────────────────────────────────────────────────────────────────────
// Reactive textures
// ────────────────────────────────────────────────────────────────────────────
const texMeta    = shallowRef<THREE.CanvasTexture | null>(null)
const texContent = shallowRef<THREE.CanvasTexture | null>(null)
const texGraph   = shallowRef<THREE.CanvasTexture | null>(null)

interface TunnelHotspotWorld {
  targetId: string
  y: number  // world-space Y offset from group origin
}
const tunnelHotspots = shallowRef<TunnelHotspotWorld[]>([])

function canvasYToWorldY(cy: number): number {
  // canvas [0, CH] → world [-SHEET_H/2, SHEET_H/2]
  return (1 - cy / CH) * SHEET_H - SHEET_H / 2
}

function rebuild() {
  texMeta.value?.dispose()
  texContent.value?.dispose()
  texGraph.value?.dispose()

  if (!store.selectedDrawer) {
    texMeta.value = null
    texContent.value = null
    texGraph.value = null
    tunnelHotspots.value = []
    return
  }

  const metaCanvas = buildMetaCanvas()
  const contentCanvas = buildContentCanvas()
  const { canvas: graphCanvas, hotspots } = buildGraphCanvas()

  const makeTex = (c: HTMLCanvasElement) => {
    const t = new THREE.CanvasTexture(c)
    t.needsUpdate = true
    return t
  }

  texMeta.value    = makeTex(metaCanvas)
  texContent.value = makeTex(contentCanvas)
  texGraph.value   = makeTex(graphCanvas)

  tunnelHotspots.value = hotspots.map(h => ({
    targetId: h.targetId,
    y: canvasYToWorldY(h.canvasY),
  }))
}

watch(() => store.selectedDrawer, rebuild, { immediate: true })

onBeforeUnmount(() => {
  texMeta.value?.dispose()
  texContent.value?.dispose()
  texGraph.value?.dispose()
})

// ────────────────────────────────────────────────────────────────────────────
// Per-sheet material helpers
// ────────────────────────────────────────────────────────────────────────────
function makePaperMat(tex: THREE.CanvasTexture | null) {
  return new THREE.MeshBasicMaterial({
    map: tex ?? undefined,
    transparent: true,
    side: THREE.FrontSide,
  })
}

// ────────────────────────────────────────────────────────────────────────────
// Click handlers
// ────────────────────────────────────────────────────────────────────────────
function onSheetClick(e: any) {
  e.stopPropagation?.()
  if (store.isTransitioning) return
  store.closeDrawer()
}

function onTunnelClick(e: any, targetId: string) {
  e.stopPropagation?.()
  if (store.isTransitioning) return
  store.goToTunnelTarget(targetId)
}

// Sheet X positions in world space
const SHEET_X = [-1.9, 0, 1.9] as const
</script>

<template>
  <!-- Group at y=1.6, facing +Z -->
  <TresGroup :position="[0, 1.6, 0]">

    <!-- Sheet A: meta -->
    <TresGroup :position-x="SHEET_X[0]">
      <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />
      <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />
      <TresMesh
        :geometry="planeGeo"
        :material="makePaperMat(texMeta)"
        @click="onSheetClick"
      />
    </TresGroup>

    <!-- Sheet B: content (center) -->
    <TresGroup :position-x="SHEET_X[1]">
      <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />
      <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />
      <TresMesh
        :geometry="planeGeo"
        :material="makePaperMat(texContent)"
        @click="onSheetClick"
      />
    </TresGroup>

    <!-- Sheet C: key points + tunnels -->
    <TresGroup :position-x="SHEET_X[2]">
      <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />
      <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />
      <TresMesh
        :geometry="planeGeo"
        :material="makePaperMat(texGraph)"
        @click="onSheetClick"
      />
      <!-- Tunnel hotspots on Sheet C -->
      <TresMesh
        v-for="hs in tunnelHotspots"
        :key="hs.targetId"
        :geometry="hotspotGeo"
        :material="hotspotMat"
        :position-y="hs.y"
        :position-z="0.001"
        @click="(e: any) => onTunnelClick(e, hs.targetId)"
      />
    </TresGroup>

  </TresGroup>
</template>
