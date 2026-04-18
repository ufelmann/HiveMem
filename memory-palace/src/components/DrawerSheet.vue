<script setup lang="ts">
import { computed, watch, onBeforeUnmount, shallowRef } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'

const store = useNavigationStore()

// ────────────────────────────────────────────────────────────────────────────
// Canvas constants
// ────────────────────────────────────────────────────────────────────────────
const CW = 1024
const CH = 1434  // ~1:1.4 (A-series)

const COLOR_BG       = '#f2ede4'
const COLOR_HEADER   = '#0a3040'
const COLOR_TITLE    = '#00BFFF'
const COLOR_META     = '#555566'
const COLOR_BODY     = '#222233'
const COLOR_DIVIDER  = 'rgba(0,191,255,0.4)'
const COLOR_SECTION  = '#00BFFF'
const COLOR_TUNNEL   = '#005577'

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

// ────────────────────────────────────────────────────────────────────────────
// Canvas drawing
// ────────────────────────────────────────────────────────────────────────────
interface TunnelHotspot {
  targetId: string
  y: number   // world-space y position offset from group origin
}

interface SheetData {
  texture: THREE.CanvasTexture
  hotspots: TunnelHotspot[]
}

function buildSheetTexture(): SheetData {
  const drawer = store.selectedDrawer
  if (!drawer) throw new Error('no drawer selected')

  const canvas = document.createElement('canvas')
  canvas.width  = CW
  canvas.height = CH
  const ctx = canvas.getContext('2d')!

  // ── Background ──────────────────────────────────────────────────────────
  ctx.fillStyle = COLOR_BG
  ctx.fillRect(0, 0, CW, CH)

  // Cyan fade border glow
  const borderGrad = ctx.createLinearGradient(0, 0, 12, 0)
  borderGrad.addColorStop(0,   'rgba(0,191,255,0.55)')
  borderGrad.addColorStop(1,   'rgba(0,191,255,0)')
  ctx.fillStyle = borderGrad
  ctx.fillRect(0, 0, 12, CH)

  const borderGradR = ctx.createLinearGradient(CW - 12, 0, CW, 0)
  borderGradR.addColorStop(0,  'rgba(0,191,255,0)')
  borderGradR.addColorStop(1,  'rgba(0,191,255,0.55)')
  ctx.fillStyle = borderGradR
  ctx.fillRect(CW - 12, 0, 12, CH)

  const borderGradT = ctx.createLinearGradient(0, 0, 0, 12)
  borderGradT.addColorStop(0,  'rgba(0,191,255,0.55)')
  borderGradT.addColorStop(1,  'rgba(0,191,255,0)')
  ctx.fillStyle = borderGradT
  ctx.fillRect(0, 0, CW, 12)

  const borderGradB = ctx.createLinearGradient(0, CH - 12, 0, CH)
  borderGradB.addColorStop(0,  'rgba(0,191,255,0)')
  borderGradB.addColorStop(1,  'rgba(0,191,255,0.55)')
  ctx.fillStyle = borderGradB
  ctx.fillRect(0, CH - 12, CW, 12)

  // ── Header strip (top 12%) ───────────────────────────────────────────────
  const HEADER_H = Math.round(CH * 0.12)
  const headerGrad = ctx.createLinearGradient(0, 0, 0, HEADER_H)
  headerGrad.addColorStop(0, COLOR_HEADER)
  headerGrad.addColorStop(1, '#0d2030')
  ctx.fillStyle = headerGrad
  ctx.fillRect(0, 0, CW, HEADER_H)

  ctx.font = 'bold 52px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_TITLE
  ctx.textBaseline = 'middle'
  const maxTitleW = CW - 80
  const titleLines = wrapText(ctx, drawer.title, maxTitleW)
  const lineH = 60
  const titleBlockH = titleLines.length * lineH
  const titleY = (HEADER_H - titleBlockH) / 2 + lineH / 2
  titleLines.forEach((line, i) => {
    ctx.fillText(line, 40, titleY + i * lineH)
  })

  // ── Meta row ─────────────────────────────────────────────────────────────
  let cursor = HEADER_H + 28

  ctx.font = '28px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = COLOR_META
  ctx.textBaseline = 'top'
  ctx.fillText(`${drawer.wing}  /  ${drawer.hall}  /  ${drawer.room}`, 40, cursor)

  // Status chip
  const statusLabel = drawer.status === 'pending' ? 'PENDING' : 'COMMITTED'
  const statusColor = drawer.status === 'pending' ? '#FF8C00' : '#00AA66'
  const chipW = 160
  const chipH = 36
  const chipX = CW - 40 - chipW
  ctx.fillStyle = statusColor
  ctx.beginPath()
  ctx.roundRect(chipX, cursor - 4, chipW, chipH, 6)
  ctx.fill()
  ctx.font = 'bold 22px "Segoe UI", Arial, sans-serif'
  ctx.fillStyle = '#fff'
  ctx.textAlign = 'center'
  ctx.fillText(statusLabel, chipX + chipW / 2, cursor + 4)
  ctx.textAlign = 'left'

  cursor += 52

  // ── Divider ───────────────────────────────────────────────────────────────
  function drawDivider(y: number) {
    ctx.strokeStyle = COLOR_DIVIDER
    ctx.lineWidth = 1.5
    ctx.beginPath()
    ctx.moveTo(32, y)
    ctx.lineTo(CW - 32, y)
    ctx.stroke()
  }

  drawDivider(cursor)
  cursor += 20

  // ── Body content (first 1200 chars) ──────────────────────────────────────
  const MARGIN = 40
  const TEXT_W = CW - MARGIN * 2
  const bodyText = drawer.content.slice(0, 1200)

  ctx.font = '26px "Courier New", Courier, monospace'
  ctx.fillStyle = COLOR_BODY
  ctx.textBaseline = 'top'
  const bodyLines = wrapText(ctx, bodyText, TEXT_W)
  const BODY_LINE_H = 36
  for (const line of bodyLines) {
    if (cursor + BODY_LINE_H > CH * 0.55) {
      ctx.fillStyle = COLOR_META
      ctx.font = '22px "Segoe UI", Arial, sans-serif'
      ctx.fillText('…', MARGIN, cursor)
      cursor += BODY_LINE_H
      break
    }
    ctx.fillText(line, MARGIN, cursor)
    cursor += BODY_LINE_H
  }

  cursor += 12
  drawDivider(cursor)
  cursor += 20

  // ── Key Points ────────────────────────────────────────────────────────────
  if (drawer.keyPoints.length > 0) {
    ctx.font = 'bold 30px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Key Points', MARGIN, cursor)
    cursor += 42

    ctx.font = '24px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_BODY
    for (const kp of drawer.keyPoints) {
      if (cursor > CH * 0.72) break
      const kpLines = wrapText(ctx, `• ${kp}`, TEXT_W - 20)
      for (const line of kpLines) {
        ctx.fillText(line, MARGIN + 10, cursor)
        cursor += 32
      }
      cursor += 4
    }

    cursor += 12
    drawDivider(cursor)
    cursor += 20
  }

  // ── Insight ───────────────────────────────────────────────────────────────
  if (drawer.insight) {
    ctx.font = 'bold 30px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Insight', MARGIN, cursor)
    cursor += 42

    ctx.font = 'italic 25px "Georgia", serif'
    ctx.fillStyle = COLOR_BODY
    const insightLines = wrapText(ctx, drawer.insight, TEXT_W - 40)
    for (const line of insightLines) {
      if (cursor > CH * 0.82) break
      ctx.fillText(line, MARGIN + 20, cursor)
      cursor += 34
    }

    cursor += 12
    drawDivider(cursor)
    cursor += 20
  }

  // ── Tunnels ───────────────────────────────────────────────────────────────
  const hotspots: TunnelHotspot[] = []

  if (drawer.tunnels.length > 0) {
    ctx.font = 'bold 30px "Segoe UI", Arial, sans-serif'
    ctx.fillStyle = COLOR_SECTION
    ctx.textBaseline = 'top'
    ctx.fillText('Tunnels', MARGIN, cursor)
    cursor += 44

    ctx.font = '24px "Segoe UI", Arial, sans-serif'

    for (const tunnel of drawer.tunnels) {
      if (cursor > CH - 80) break
      const targetTitle = store.drawersById[tunnel.targetId]?.title ?? tunnel.targetId

      // Row background on hover — just draw a subtle row fill
      const rowY = cursor - 4
      const rowH = 40
      ctx.fillStyle = 'rgba(0,191,255,0.06)'
      ctx.fillRect(MARGIN, rowY, TEXT_W, rowH)

      ctx.fillStyle = COLOR_TUNNEL
      ctx.fillText(`→ `, MARGIN, cursor)
      const arrowW = ctx.measureText('→ ').width

      ctx.fillStyle = COLOR_BODY
      const labelText = `${targetTitle}  (${tunnel.relation})`
      ctx.fillText(labelText, MARGIN + arrowW, cursor)

      // Record hotspot: map canvas Y → world Y
      // Canvas Y range [0, CH] maps to world Y range [-SHEET_H/2, SHEET_H/2]
      // SHEET_H = 2.52, so worldY = (1 - cursor/CH) * 2.52 - 1.26
      const SHEET_H = 2.52
      const normalised = 1 - (cursor + rowH / 2) / CH
      const worldY = normalised * SHEET_H - SHEET_H / 2
      hotspots.push({ targetId: tunnel.targetId, y: worldY })

      cursor += rowH + 6
    }
  }

  const texture = new THREE.CanvasTexture(canvas)
  texture.needsUpdate = true
  return { texture, hotspots }
}

// ────────────────────────────────────────────────────────────────────────────
// Reactive texture + hotspots
// ────────────────────────────────────────────────────────────────────────────
const sheetTexture   = shallowRef<THREE.CanvasTexture | null>(null)
const tunnelHotspots = shallowRef<TunnelHotspot[]>([])

function rebuild() {
  if (sheetTexture.value) {
    sheetTexture.value.dispose()
  }
  if (!store.selectedDrawer) {
    sheetTexture.value = null
    tunnelHotspots.value = []
    return
  }
  const { texture, hotspots } = buildSheetTexture()
  sheetTexture.value   = texture
  tunnelHotspots.value = hotspots
}

watch(() => store.selectedDrawer, rebuild, { immediate: true })

onBeforeUnmount(() => {
  if (sheetTexture.value) sheetTexture.value.dispose()
})

// ────────────────────────────────────────────────────────────────────────────
// Plane geometry + rim geometry (created once, static)
// ────────────────────────────────────────────────────────────────────────────
const SHEET_W = 1.8
const SHEET_H = 2.52

const planeGeo = new THREE.PlaneGeometry(SHEET_W, SHEET_H)
const rimGeo   = new THREE.PlaneGeometry(SHEET_W + 0.06, SHEET_H + 0.06)
// EdgesGeometry for the glowing outline frame
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

// Hotspot geometry template (reused per hotspot)
const hotspotGeo = new THREE.PlaneGeometry(1.6, 0.18)
const hotspotMat = new THREE.MeshBasicMaterial({
  transparent: true,
  opacity: 0,
  side: THREE.DoubleSide,
  depthWrite: false,
})

// ────────────────────────────────────────────────────────────────────────────
// Click handlers
// ────────────────────────────────────────────────────────────────────────────
function onSheetClick(e: any) {
  e.stopPropagation?.()
  store.closeDrawer()
}

function onTunnelClick(e: any, targetId: string) {
  e.stopPropagation?.()
  store.goToTunnelTarget(targetId)
}

// ────────────────────────────────────────────────────────────────────────────
// Computed material (updates when texture changes)
// ────────────────────────────────────────────────────────────────────────────
const paperMat = computed(() =>
  new THREE.MeshBasicMaterial({
    map: sheetTexture.value ?? undefined,
    transparent: true,
    side: THREE.FrontSide,
  })
)
</script>

<template>
  <!-- Group placed in front of room centre, facing +Z camera -->
  <TresGroup :position="[0, 1.5, 2]">
    <!-- Rim glow plane (slightly behind main sheet) -->
    <TresMesh :geometry="rimGeo" :material="rimMat" :position-z="-0.002" />

    <!-- Edge outline frame -->
    <primitive :object="new THREE.LineSegments(edgesGeo, lineMat)" :position-z="-0.001" />

    <!-- Main paper plane -->
    <TresMesh
      :geometry="planeGeo"
      :material="paperMat"
      @click="onSheetClick"
    />

    <!-- Invisible tunnel hotspots -->
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
</template>
