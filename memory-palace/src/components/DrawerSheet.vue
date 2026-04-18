<script setup lang="ts">
import { watch, onBeforeUnmount, shallowRef, computed } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'

const store = useNavigationStore()

// ────────────────────────────────────────────────────────────────────────────
// Canvas constants
// ────────────────────────────────────────────────────────────────────────────
const CW = 768          // canvas width (always fixed)
const CH_NORMAL = 1075  // canvas height when not focused
const CH_MAX = 3072     // canvas height upper bound when focused

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

function drawDivider(ctx: CanvasRenderingContext2D, y: number, canvasW: number) {
  ctx.strokeStyle = COLOR_DIVIDER
  ctx.lineWidth = 1.5
  ctx.beginPath()
  ctx.moveTo(28, y)
  ctx.lineTo(canvasW - 28, y)
  ctx.stroke()
}

function drawBorderGlow(ctx: CanvasRenderingContext2D, w: number, h: number) {
  const gL = ctx.createLinearGradient(0, 0, 14, 0)
  gL.addColorStop(0, 'rgba(0,191,255,0.55)'); gL.addColorStop(1, 'rgba(0,191,255,0)')
  ctx.fillStyle = gL; ctx.fillRect(0, 0, 14, h)
  const gR = ctx.createLinearGradient(w - 14, 0, w, 0)
  gR.addColorStop(0, 'rgba(0,191,255,0)'); gR.addColorStop(1, 'rgba(0,191,255,0.55)')
  ctx.fillStyle = gR; ctx.fillRect(w - 14, 0, 14, h)
  const gT = ctx.createLinearGradient(0, 0, 0, 14)
  gT.addColorStop(0, 'rgba(0,191,255,0.55)'); gT.addColorStop(1, 'rgba(0,191,255,0)')
  ctx.fillStyle = gT; ctx.fillRect(0, 0, w, 14)
  const gB = ctx.createLinearGradient(0, h - 14, 0, h)
  gB.addColorStop(0, 'rgba(0,191,255,0)'); gB.addColorStop(1, 'rgba(0,191,255,0.55)')
  ctx.fillStyle = gB; ctx.fillRect(0, h - 14, w, 14)
}

/** Draw a standard title header strip. Returns cursor Y after header. */
function drawHeader(ctx: CanvasRenderingContext2D, title: string): number {
  const HEADER_H = Math.round(CH_NORMAL * 0.14)
  const headerGrad = ctx.createLinearGradient(0, 0, 0, HEADER_H)
  headerGrad.addColorStop(0, COLOR_HEADER)
  headerGrad.addColorStop(1, '#0d2030')
  ctx.fillStyle = headerGrad
  ctx.fillRect(0, 0, CW, HEADER_H)

  ctx.font = 'bold 44px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_TITLE
  ctx.textBaseline = 'middle'
  ctx.textAlign = 'left'
  const maxTitleW = CW - 60
  const titleLines = wrapText(ctx, title, maxTitleW)
  const lineH = 52
  const titleBlockH = titleLines.length * lineH
  const titleY = (HEADER_H - titleBlockH) / 2 + lineH / 2
  titleLines.forEach((line, i) => {
    ctx.fillText(line, 30, titleY + i * lineH)
  })
  return HEADER_H
}

/** Trim a tall canvas to actualUsedHeight + padding, returning a new canvas. */
function trimCanvas(src: HTMLCanvasElement, usedHeight: number): HTMLCanvasElement {
  const finalH = Math.max(Math.min(usedHeight + 100, src.height), 200)
  const dst = document.createElement('canvas')
  dst.width = CW
  dst.height = finalH
  const dstCtx = dst.getContext('2d')!
  dstCtx.drawImage(src, 0, 0)
  return dst
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet A: Summary only (title header + metadata row + L1 summary)
// ────────────────────────────────────────────────────────────────────────────
function buildSummaryCanvas(focused: boolean): HTMLCanvasElement {
  const drawer = store.selectedDrawer!
  const CH = focused ? CH_MAX : CH_NORMAL
  const raw = document.createElement('canvas')
  raw.width = CW; raw.height = CH
  const ctx = raw.getContext('2d')!

  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)

  // Header
  const headerH = drawHeader(ctx, drawer.title)
  let cursor = headerH + 22

  // Wing / hall / room
  ctx.font = '24px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_META
  ctx.textBaseline = 'top'
  ctx.textAlign = 'left'
  ctx.fillText(`${drawer.wing}  /  ${drawer.hall}  /  ${drawer.room}`, 30, cursor)

  // Status chip
  const statusLabel = drawer.status === 'pending' ? 'PENDING' : 'COMMITTED'
  const statusColor = drawer.status === 'pending' ? '#FF8C00' : '#00AA66'
  const chipW = 140; const chipH = 30; const chipX = CW - 30 - chipW
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
  drawDivider(ctx, cursor, CW)
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
    const limit = focused ? Infinity : CH * 0.85
    for (const line of sumLines) {
      if (cursor > limit) break
      ctx.fillText(line, MARGIN, cursor)
      cursor += 30
    }
    cursor += 12
  } else {
    ctx.font = 'italic 22px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_META
    ctx.textBaseline = 'top'
    ctx.fillText('(no summary)', MARGIN, cursor)
    cursor += 36
  }

  if (focused) {
    drawBorderGlow(ctx, CW, CH)
    return trimCanvas(raw, cursor)
  }
  drawBorderGlow(ctx, CW, CH)
  return raw
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet B: Key Points + Insight + Tunnels
// ────────────────────────────────────────────────────────────────────────────
interface TunnelHotspot {
  targetId: string
  canvasY: number  // canvas-space Y of row centre
  canvasH: number  // total height of the canvas used
}

function buildDigestCanvas(focused: boolean): { canvas: HTMLCanvasElement; hotspots: TunnelHotspot[] } {
  const drawer = store.selectedDrawer!
  const CH = focused ? CH_MAX : CH_NORMAL
  const raw = document.createElement('canvas')
  raw.width = CW; raw.height = CH
  const ctx = raw.getContext('2d')!

  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)

  // Header
  const headerH = drawHeader(ctx, drawer.title)
  let cursor = headerH + 16

  const MARGIN = 30
  const TEXT_W = CW - MARGIN * 2
  const hotspots: TunnelHotspot[] = []

  // Key Points
  if (drawer.keyPoints.length > 0) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.textAlign = 'left'
    ctx.fillText('Key Points', MARGIN, cursor)
    cursor += 36
    drawDivider(ctx, cursor, CW)
    cursor += 14

    ctx.font = '20px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_BODY
    const kpLimit = focused ? Infinity : CH * 0.45
    for (const kp of drawer.keyPoints) {
      if (cursor > kpLimit) break
      const kpLines = wrapText(ctx, `• ${kp}`, TEXT_W - 16)
      for (const line of kpLines) {
        ctx.fillText(line, MARGIN + 8, cursor)
        cursor += 28
      }
      cursor += 4
    }
    cursor += 16
    drawDivider(ctx, cursor, CW)
    cursor += 16
  }

  // Insight
  if (drawer.insight) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.textAlign = 'left'
    ctx.fillText('Insight', MARGIN, cursor)
    cursor += 36

    ctx.font = 'italic 22px "Georgia", serif'
    ctx.fillStyle = COLOR_BODY
    const insLines = wrapText(ctx, drawer.insight, TEXT_W - 30)
    const insLimit = focused ? Infinity : CH * 0.72
    for (const line of insLines) {
      if (cursor > insLimit) break
      ctx.fillText(line, MARGIN + 16, cursor)
      cursor += 30
    }
    cursor += 16
    drawDivider(ctx, cursor, CW)
    cursor += 16
  }

  // Tunnels
  if (drawer.tunnels.length > 0) {
    ctx.font = 'bold 26px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.textAlign = 'left'
    ctx.fillText('Tunnels', MARGIN, cursor)
    cursor += 36

    ctx.font = '20px "Segoe UI", Arial, sans-serif'
    for (const tunnel of drawer.tunnels) {
      if (cursor > CH - 80) break
      const targetTitle = store.drawersById[tunnel.targetId]?.title ?? tunnel.targetId

      const rowH = 36
      const rowY = cursor - 4
      ctx.fillStyle = 'rgba(0,191,255,0.06)'
      ctx.fillRect(MARGIN, rowY, TEXT_W, rowH)

      ctx.fillStyle = COLOR_TUNNEL
      ctx.textAlign = 'left'
      const label = `→ ${tunnel.relation}  ${targetTitle}`
      ctx.fillText(label, MARGIN, cursor)

      hotspots.push({ targetId: tunnel.targetId, canvasY: cursor + rowH / 2, canvasH: CH })
      cursor += rowH + 4
    }
    cursor += 16
  }

  // Close hint at bottom
  ctx.font = 'italic 18px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = 'rgba(0,0,0,0.35)'
  ctx.textAlign = 'center'
  ctx.textBaseline = 'bottom'
  ctx.fillText('click to close', CW / 2, CH - 16)
  ctx.textAlign = 'left'

  const usedH = cursor + 60

  if (focused) {
    drawBorderGlow(ctx, CW, CH)
    const trimmed = trimCanvas(raw, usedH)
    // Update hotspot canvasH to the trimmed height
    const finalH = trimmed.height
    const updatedHotspots = hotspots.map(h => ({ ...h, canvasH: finalH }))
    return { canvas: trimmed, hotspots: updatedHotspots }
  }
  drawBorderGlow(ctx, CW, CH)
  return { canvas: raw, hotspots }
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet C: Full Content
// ────────────────────────────────────────────────────────────────────────────
function buildContentCanvas(focused: boolean): HTMLCanvasElement {
  const drawer = store.selectedDrawer!
  const CH = focused ? CH_MAX : CH_NORMAL
  const raw = document.createElement('canvas')
  raw.width = CW; raw.height = CH
  const ctx = raw.getContext('2d')!

  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)

  // Header
  const headerH = drawHeader(ctx, drawer.title)
  let cursor = headerH + 16

  drawDivider(ctx, cursor, CW)
  cursor += 16

  const MARGIN = 30
  const TEXT_W = CW - MARGIN * 2
  const BODY_LINE_H = 26

  ctx.font = '18px "Courier New", Courier, monospace'
  ctx.fillStyle = COLOR_BODY
  ctx.textBaseline = 'top'
  ctx.textAlign = 'left'
  const contentLines = wrapText(ctx, drawer.content, TEXT_W)

  for (const line of contentLines) {
    if (!focused && cursor + BODY_LINE_H > CH - 20) {
      ctx.fillStyle = COLOR_META
      ctx.font = '18px "Segoe UI", Arial, sans-serif'
      ctx.fillText('…', MARGIN, cursor)
      break
    }
    ctx.fillText(line, MARGIN, cursor)
    cursor += BODY_LINE_H
  }

  const usedH = cursor + 20

  if (focused) {
    drawBorderGlow(ctx, CW, CH)
    return trimCanvas(raw, usedH)
  }
  drawBorderGlow(ctx, CW, CH)
  return raw
}

// ────────────────────────────────────────────────────────────────────────────
// Sheet geometry + materials — static
// ────────────────────────────────────────────────────────────────────────────
const SHEET_W = 1.6
const SHEET_H = 2.24

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
// Per-sheet reactive state
// ────────────────────────────────────────────────────────────────────────────
interface SheetState {
  tex: THREE.CanvasTexture | null
  canvasH: number   // actual canvas pixel height (may be trimmed)
  scaleY: number    // world-scale-Y to maintain aspect with fixed width
}

const sheetA = shallowRef<SheetState>({ tex: null, canvasH: CH_NORMAL, scaleY: 1 })
const sheetB = shallowRef<SheetState>({ tex: null, canvasH: CH_NORMAL, scaleY: 1 })
const sheetC = shallowRef<SheetState>({ tex: null, canvasH: CH_NORMAL, scaleY: 1 })

interface TunnelHotspotWorld {
  targetId: string
  y: number  // world-space Y offset from sheet B's group origin
}
const tunnelHotspots = shallowRef<TunnelHotspotWorld[]>([])

function canvasYToWorldY(cy: number, canvasH: number, sheetScaleY: number): number {
  // canvas [0, canvasH] → world [-sheetWorldH/2, +sheetWorldH/2]
  const sheetWorldH = SHEET_H * sheetScaleY
  return (1 - cy / canvasH) * sheetWorldH - sheetWorldH / 2
}

function makeTex(c: HTMLCanvasElement): THREE.CanvasTexture {
  const t = new THREE.CanvasTexture(c)
  t.needsUpdate = true
  return t
}

function makeSheetState(canvas: HTMLCanvasElement): SheetState {
  const canvasH = canvas.height
  // scaleY: maintain aspect ratio relative to normal SHEET_H
  // At CH_NORMAL height, scaleY=1. For taller canvases it scales up.
  const scaleY = canvasH / CH_NORMAL
  return { tex: makeTex(canvas), canvasH, scaleY }
}

function rebuild() {
  sheetA.value.tex?.dispose()
  sheetB.value.tex?.dispose()
  sheetC.value.tex?.dispose()

  if (!store.selectedDrawer) {
    sheetA.value = { tex: null, canvasH: CH_NORMAL, scaleY: 1 }
    sheetB.value = { tex: null, canvasH: CH_NORMAL, scaleY: 1 }
    sheetC.value = { tex: null, canvasH: CH_NORMAL, scaleY: 1 }
    tunnelHotspots.value = []
    return
  }

  const focusedIdx = store.focusedSheet

  // Sheet A: Summary
  const aCanvas = buildSummaryCanvas(focusedIdx === 0)
  sheetA.value = makeSheetState(aCanvas)

  // Sheet B: Key Points + Insight + Tunnels
  const { canvas: bCanvas, hotspots } = buildDigestCanvas(focusedIdx === 1)
  const bState = makeSheetState(bCanvas)
  sheetB.value = bState

  // Sheet C: Full Content
  const cCanvas = buildContentCanvas(focusedIdx === 2)
  sheetC.value = makeSheetState(cCanvas)

  // Tunnel hotspots in world coordinates (relative to sheet B group)
  tunnelHotspots.value = hotspots.map(h => ({
    targetId: h.targetId,
    y: canvasYToWorldY(h.canvasY, h.canvasH, bState.scaleY),
  }))
}

watch(
  () => [store.selectedDrawer, store.focusedSheet] as const,
  rebuild,
  { immediate: true },
)

onBeforeUnmount(() => {
  sheetA.value.tex?.dispose()
  sheetB.value.tex?.dispose()
  sheetC.value.tex?.dispose()
})

// ────────────────────────────────────────────────────────────────────────────
// Per-sheet plane geometry — reactive to scaleY
// ────────────────────────────────────────────────────────────────────────────
function makePaperMat(tex: THREE.CanvasTexture | null) {
  return new THREE.MeshBasicMaterial({
    map: tex ?? undefined,
    transparent: true,
    side: THREE.FrontSide,
  })
}

function makePlaneGeo(scaleY: number) {
  return new THREE.PlaneGeometry(SHEET_W, SHEET_H * scaleY)
}

// ────────────────────────────────────────────────────────────────────────────
// Focus layout
// ────────────────────────────────────────────────────────────────────────────
const SHEET_X = [-1.9, 0, 1.9] as const
const FOCUSED_SCALE = 1.6
const FOCUSED_Z = 0.3

// Per-sheet computed positions/visibility
const layoutA = computed(() => {
  const f = store.focusedSheet
  if (f === null)   return { x: SHEET_X[0], visible: true, scale: 1, z: 0 }
  if (f === 0)      return { x: 0, visible: true, scale: FOCUSED_SCALE, z: FOCUSED_Z }
  return { x: SHEET_X[0], visible: false, scale: 1, z: 0 }
})

const layoutB = computed(() => {
  const f = store.focusedSheet
  if (f === null)   return { x: SHEET_X[1], visible: true, scale: 1, z: 0 }
  if (f === 1)      return { x: 0, visible: true, scale: FOCUSED_SCALE, z: FOCUSED_Z }
  return { x: SHEET_X[1], visible: false, scale: 1, z: 0 }
})

const layoutC = computed(() => {
  const f = store.focusedSheet
  if (f === null)   return { x: SHEET_X[2], visible: true, scale: 1, z: 0 }
  if (f === 2)      return { x: 0, visible: true, scale: FOCUSED_SCALE, z: FOCUSED_Z }
  return { x: SHEET_X[2], visible: false, scale: 1, z: 0 }
})

// ────────────────────────────────────────────────────────────────────────────
// Click handlers
// ────────────────────────────────────────────────────────────────────────────
function onSheetClick(e: any, idx: 0 | 1 | 2) {
  e.stopPropagation?.()
  if (store.isTransitioning) return
  if (store.focusedSheet === null) {
    store.focusSheet(idx)
  } else {
    store.unfocusSheet()
  }
}

function onTunnelClick(e: any, targetId: string) {
  e.stopPropagation?.()
  if (store.isTransitioning) return
  store.goToTunnelTarget(targetId)
}
</script>

<template>
  <!-- Group at y=1.6, facing +Z -->
  <TresGroup :position="[0, 1.6, 0]">

    <!-- Sheet A: Summary (left, x=-1.9, index 0) -->
    <TresGroup
      :position="[layoutA.x, 0, layoutA.z]"
      :scale="[layoutA.scale, layoutA.scale, 1]"
      :visible="layoutA.visible"
    >
      <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />
      <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />
      <TresMesh
        :geometry="makePlaneGeo(sheetA.scaleY)"
        :material="makePaperMat(sheetA.tex)"
        @click="(e: any) => onSheetClick(e, 0)"
      />
    </TresGroup>

    <!-- Sheet B: Key Points + Insight + Tunnels (center, x=0, index 1) -->
    <TresGroup
      :position="[layoutB.x, 0, layoutB.z]"
      :scale="[layoutB.scale, layoutB.scale, 1]"
      :visible="layoutB.visible"
    >
      <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />
      <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />
      <TresMesh
        :geometry="makePlaneGeo(sheetB.scaleY)"
        :material="makePaperMat(sheetB.tex)"
        @click="(e: any) => onSheetClick(e, 1)"
      />
      <!-- Tunnel hotspots on Sheet B -->
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

    <!-- Sheet C: Full Content (right, x=+1.9, index 2) -->
    <TresGroup
      :position="[layoutC.x, 0, layoutC.z]"
      :scale="[layoutC.scale, layoutC.scale, 1]"
      :visible="layoutC.visible"
    >
      <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />
      <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />
      <TresMesh
        :geometry="makePlaneGeo(sheetC.scaleY)"
        :material="makePaperMat(sheetC.tex)"
        @click="(e: any) => onSheetClick(e, 2)"
      />
    </TresGroup>

  </TresGroup>
</template>
