<script setup lang="ts">
import { computed, onBeforeUnmount, shallowRef, watch } from 'vue'
import * as THREE from 'three'
import gsap from 'gsap'
import { useNavigationStore } from '../../stores/navigation'
import type { GoldbergCell } from '../../composables/goldbergMath'
import type { WingPalette } from '../../composables/wingPalette'

const props = defineProps<{
  cell: GoldbergCell
  wingName: string | null
  palette: WingPalette | null
  isAccentPink?: boolean
  isAccentViolet?: boolean
}>()

const store = useNavigationStore()

const R_SPHERE = 3

function rng(seed: number): () => number {
  let s = seed >>> 0
  return () => {
    s = (s * 1664525 + 1013904223) & 0xffffffff
    return ((s >>> 0) / 0xffffffff)
  }
}

const hueShift = computed(() => {
  const r = rng(props.cell.index + 17)
  return (r() * 30) - 15
})

const latY = computed(() => props.cell.centroid[1] / R_SPHERE)

function shiftColor(input: string, hueDelta: number, lightDelta: number): string {
  let h = 0, s = 0, l = 0
  if (input.startsWith('hsl')) {
    const match = input.match(/hsl\(([\d.]+),\s*([\d.]+)%,\s*([\d.]+)%/)
    if (match) { h = Number(match[1]); s = Number(match[2]) / 100; l = Number(match[3]) / 100 }
  } else {
    const c = input.replace('#', '')
    const n = parseInt(c.length === 3 ? c.split('').map((x) => x + x).join('') : c, 16)
    const r = ((n >> 16) & 255) / 255, g = ((n >> 8) & 255) / 255, b = (n & 255) / 255
    const max = Math.max(r, g, b), min = Math.min(r, g, b)
    l = (max + min) / 2
    if (max !== min) {
      const d = max - min
      s = l > 0.5 ? d / (2 - max - min) : d / (max + min)
      switch (max) {
        case r: h = ((g - b) / d + (g < b ? 6 : 0)); break
        case g: h = (b - r) / d + 2; break
        case b: h = (r - g) / d + 4; break
      }
      h *= 60
    }
  }
  const nH = ((h + hueDelta) % 360 + 360) % 360
  const nS = Math.max(0, Math.min(1, s))
  const nL = Math.max(0.02, Math.min(0.98, l + lightDelta))
  return `hsl(${nH.toFixed(1)}, ${(nS * 100).toFixed(1)}%, ${(nL * 100).toFixed(1)}%)`
}

function hslToHex(h: number, s: number, l: number): string {
  const a = s * Math.min(l, 1 - l)
  const f = (n: number) => {
    const k = (n + h / 30) % 12
    const colour = l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))
    return Math.round(colour * 255).toString(16).padStart(2, '0')
  }
  return `#${f(0)}${f(8)}${f(4)}`
}

function hexToRgba(input: string, alpha: number): string {
  if (input.startsWith('hsl')) {
    const match = input.match(/hsl\(([\d.]+),\s*([\d.]+)%,\s*([\d.]+)%/)
    if (match) {
      const h = Number(match[1]), s = Number(match[2]) / 100, l = Number(match[3]) / 100
      const hex = hslToHex(h, s, l)
      return hexToRgba(hex, alpha)
    }
  }
  const c = input.replace('#', '')
  const n = parseInt(c.length === 3 ? c.split('').map((x) => x + x).join('') : c, 16)
  const r = (n >> 16) & 255, g = (n >> 8) & 255, b = n & 255
  return `rgba(${r},${g},${b},${alpha})`
}

function buildCellCanvas(): HTMLCanvasElement {
  const S = 256
  const canvas = document.createElement('canvas')
  canvas.width = S; canvas.height = S
  const ctx = canvas.getContext('2d')!

  let glow: string, base: string, tint: string
  if (props.isAccentPink) {
    glow = '#FFB8CF'; base = '#FF6B9D'; tint = '#6e2450'
  } else if (props.isAccentViolet) {
    glow = '#c9b8ff'; base = '#9a6bff'; tint = '#3e2a70'
  } else if (props.palette) {
    const shift = hueShift.value + 25 * -latY.value
    const lShift = 0.1 * latY.value
    glow = shiftColor(props.palette.glow, shift, lShift)
    base = shiftColor(props.palette.base, shift, lShift)
    tint = shiftColor(props.palette.tint, shift, lShift)
  } else {
    glow = '#2a2a2a'; base = '#1a1a1a'; tint = '#0d0d0d'
  }

  const grad = ctx.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2)
  grad.addColorStop(0, glow)
  grad.addColorStop(0.45, base)
  grad.addColorStop(1, tint)
  ctx.fillStyle = grad
  ctx.fillRect(0, 0, S, S)

  const r = rng(props.cell.index + 42)
  const shapeCount = 2 + Math.floor(r() * 4)
  for (let i = 0; i < shapeCount; i++) {
    const x = r() * S, y = r() * S, w = 8 + r() * 60, h = 8 + r() * 60
    const alpha = 0.08 + r() * 0.07
    ctx.fillStyle = hexToRgba(base, alpha)
    if (r() < 0.5) {
      ctx.fillRect(x, y, w, h)
    } else {
      ctx.beginPath(); ctx.arc(x, y, Math.min(w, h) / 2, 0, Math.PI * 2); ctx.fill()
    }
  }

  return canvas
}

const geometry = shallowRef<THREE.BufferGeometry | null>(null)
const edgesGeometry = shallowRef<THREE.BufferGeometry | null>(null)
const tex = shallowRef<THREE.CanvasTexture | null>(null)

function buildGeometry() {
  const centroid = new THREE.Vector3(...props.cell.centroid)
  const normal = centroid.clone().normalize()
  const tangent = new THREE.Vector3()
  if (Math.abs(normal.y) < 0.99) tangent.copy(new THREE.Vector3(0, 1, 0)).cross(normal).normalize()
  else tangent.copy(new THREE.Vector3(1, 0, 0)).cross(normal).normalize()
  const bitangent = new THREE.Vector3().copy(normal).cross(tangent).normalize()

  const shape = new THREE.Shape()
  const flat: [number, number][] = props.cell.vertices.map((v) => {
    const p = new THREE.Vector3(...v).sub(centroid)
    return [p.dot(tangent) * 0.98, p.dot(bitangent) * 0.98]
  })
  shape.moveTo(flat[0][0], flat[0][1])
  for (let i = 1; i < flat.length; i++) shape.lineTo(flat[i][0], flat[i][1])
  shape.lineTo(flat[0][0], flat[0][1])

  const geo = new THREE.ExtrudeGeometry(shape, { depth: 0.08, bevelEnabled: false })
  geo.translate(0, 0, -0.04)
  geometry.value = geo

  const edges = new THREE.EdgesGeometry(geo, 1)
  edgesGeometry.value = edges

  if (tex.value) tex.value.dispose()
  const t = new THREE.CanvasTexture(buildCellCanvas())
  t.needsUpdate = true
  tex.value = t
}

watch(() => props.cell.index, buildGeometry, { immediate: true })
watch(() => [props.wingName, props.isAccentPink, props.isAccentViolet], buildGeometry)

onBeforeUnmount(() => {
  if (tex.value) tex.value.dispose()
  geometry.value?.dispose()
  edgesGeometry.value?.dispose()
})

const hovered = shallowRef(false)
const intensity = shallowRef(0.15)
let intensityTween: gsap.core.Tween | null = null
watch(
  () => store.hoveredWing === props.wingName && props.wingName !== null,
  (active) => {
    const proxy = { v: intensity.value }
    if (intensityTween) intensityTween.kill()
    intensityTween = gsap.to(proxy, {
      v: active ? 0.55 : 0.15,
      duration: 0.2,
      onUpdate: () => { intensity.value = proxy.v },
    })
  },
)
const scale = computed(() => (hovered.value && props.wingName ? 1.03 : 1))

function onPointerOver(e: any) {
  e.stopPropagation?.()
  hovered.value = true
  if (props.wingName) store.setHoveredWing(props.wingName)
}
function onPointerLeave(e: any) {
  e.stopPropagation?.()
  hovered.value = false
  if (store.hoveredWing === props.wingName) store.setHoveredWing(null)
}
function onClick(e: any) {
  e.stopPropagation?.()
  if (!props.wingName || store.isTransitioning) return
  const canHover = typeof window !== 'undefined' && window.matchMedia('(hover: hover)').matches
  if (!canHover && store.hoveredWing !== props.wingName) {
    store.setHoveredWing(props.wingName)
    return
  }
  store.enterWing(props.wingName)
}

const groupPos = computed<[number, number, number]>(() => props.cell.centroid)

// Rotate the cell so its local +Z axis (extrusion direction) aligns with the
// outward normal from the sphere centre to this cell's centroid.
const cellRotation = computed<[number, number, number]>(() => {
  const normal = new THREE.Vector3(...props.cell.centroid).normalize()
  const quat = new THREE.Quaternion().setFromUnitVectors(new THREE.Vector3(0, 0, 1), normal)
  const euler = new THREE.Euler().setFromQuaternion(quat, 'XYZ')
  return [euler.x, euler.y, euler.z]
})
</script>

<template>
  <TresGroup
    :position="groupPos"
    :rotation="cellRotation"
    :scale="scale"
    @pointer-over="onPointerOver"
    @pointer-leave="onPointerLeave"
    @click="onClick"
  >
    <TresMesh :geometry="geometry ?? undefined" :visible="geometry !== null">
      <TresMeshPhysicalMaterial
        :color="'#1c1c1c'"
        :metalness="0.85"
        :roughness="0.15"
        :transmission="0.55"
        :thickness="0.3"
        :ior="1.45"
        :emissive="palette?.tint ?? '#0d0d0d'"
        :emissive-intensity="intensity"
        :transparent="true"
        :opacity="0.85"
        :side="THREE.DoubleSide"
      />
    </TresMesh>

    <TresMesh :geometry="geometry ?? undefined" :visible="geometry !== null" :position-z="0.12" :scale="0.85">
      <TresMeshBasicMaterial :map="tex" :transparent="true" :opacity="0.95" />
    </TresMesh>

    <primitive
      v-if="edgesGeometry"
      :object="new THREE.LineSegments(edgesGeometry, new THREE.LineBasicMaterial({ color: '#2a2a2a' }))"
    />
  </TresGroup>
</template>
