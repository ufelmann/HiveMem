<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'
import { makeStoneFloorTextureWithRepeat, makeHexWallTextureWithRepeat } from '../composables/useTextures'

const PI = Math.PI

const store = useNavigationStore()

const R_T = 2.2
const APOTHEM = R_T * Math.cos(PI / 6)
const EDGE = R_T

const rooms = computed(() => store.hallObj?.rooms ?? [])
const wingColor = computed(() => store.wingObj?.color ?? '#00BFFF')
const tunnelLength = computed(() => Math.max(12, rooms.value.length * 3))

const floorTexture = computed(() => makeStoneFloorTextureWithRepeat(tunnelLength.value / 4, 1))
const wallTexture  = computed(() => makeHexWallTextureWithRepeat(tunnelLength.value / 3, 1))

const labelTextures = new Map<string, THREE.CanvasTexture>()
function buildRoomLabelCanvas(room: string, count: number): HTMLCanvasElement {
  const W = 768, Hc = 180
  const c = document.createElement('canvas'); c.width = W; c.height = Hc
  const ctx = c.getContext('2d')!
  ctx.clearRect(0, 0, W, Hc)
  ctx.fillStyle = 'rgba(10,10,26,0.92)'
  ctx.strokeStyle = 'rgba(0,191,255,0.85)'
  ctx.lineWidth = 5
  ctx.beginPath(); ctx.roundRect(8, 8, W - 16, Hc - 16, 18); ctx.fill(); ctx.stroke()
  ctx.fillStyle = '#00BFFF'
  ctx.font = 'bold 66px "Segoe UI", Arial, sans-serif'
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
  ctx.fillText(room, W / 2, Hc / 2 - 18)
  ctx.fillStyle = '#9eb6d9'
  ctx.font = '32px "Segoe UI", Arial, sans-serif'
  ctx.fillText(`${count} drawers`, W / 2, Hc / 2 + 38)
  return c
}
function getRoomLabel(room: string, count: number): THREE.CanvasTexture {
  const key = `${room}|${count}`
  const cached = labelTextures.get(key)
  if (cached) return cached
  const tex = new THREE.CanvasTexture(buildRoomLabelCanvas(room, count))
  tex.needsUpdate = true
  labelTextures.set(key, tex)
  return tex
}
onBeforeUnmount(() => {
  for (const t of labelTextures.values()) t.dispose()
  labelTextures.clear()
})

const portalSlots = computed(() =>
  rooms.value.map((room: any, i: number) => {
    const side = i % 2 === 0 ? -1 : 1
    const pairIdx = Math.floor(i / 2)
    const x = -tunnelLength.value / 2 + 1.5 + pairIdx * 3 + 1.5
    const z = side * (APOTHEM - 0.02)
    const rotationY = side === -1 ? 0 : PI
    return { room, position: [x, 1.3, z] as [number, number, number], rotationY }
  })
)

const ceilingLights = computed(() => {
  const L = tunnelLength.value
  const count = Math.min(5, Math.max(2, Math.floor(L / 4)))
  const step = L / (count + 1)
  return Array.from({ length: count }, (_, i) => ({
    position: [-L / 2 + step * (i + 1), R_T * 0.8, 0] as [number, number, number],
  }))
})

function onPortalClick(name: string) {
  if (store.isTransitioning) return
  store.enterRoom(name)
}
</script>

<template>
  <TresGroup>
    <!-- Floor -->
    <TresMesh :rotation-x="-PI / 2" :position-y="-APOTHEM">
      <TresPlaneGeometry :args="[tunnelLength, EDGE]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="floorTexture" :roughness="0.7" />
    </TresMesh>

    <!-- Ceiling -->
    <TresMesh :rotation-x="PI / 2" :position-y="APOTHEM * 2">
      <TresPlaneGeometry :args="[tunnelLength, EDGE]" />
      <TresMeshStandardMaterial :color="'#06060f'" :emissive="wingColor" :emissive-intensity="0.05" />
    </TresMesh>

    <!-- Upper-left slanted -->
    <TresMesh :position="[0, APOTHEM, -APOTHEM / 2]" :rotation-x="PI / 3">
      <TresPlaneGeometry :args="[tunnelLength, EDGE]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="wallTexture" :side="THREE.DoubleSide" />
    </TresMesh>

    <!-- Upper-right slanted -->
    <TresMesh :position="[0, APOTHEM, APOTHEM / 2]" :rotation-x="-PI / 3">
      <TresPlaneGeometry :args="[tunnelLength, EDGE]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="wallTexture" :side="THREE.DoubleSide" />
    </TresMesh>

    <!-- Lower-left vertical (portal wall) -->
    <TresMesh :position="[0, 0.4, -APOTHEM]" :rotation-y="0">
      <TresPlaneGeometry :args="[tunnelLength, APOTHEM * 1.4]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="wallTexture" :side="THREE.DoubleSide" />
    </TresMesh>

    <!-- Lower-right vertical (portal wall) -->
    <TresMesh :position="[0, 0.4, APOTHEM]" :rotation-y="PI">
      <TresPlaneGeometry :args="[tunnelLength, APOTHEM * 1.4]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="wallTexture" :side="THREE.DoubleSide" />
    </TresMesh>

    <!-- Room portals -->
    <TresGroup
      v-for="slot in portalSlots"
      :key="slot.room.name"
      :position="slot.position"
      :rotation-y="slot.rotationY"
      @click="onPortalClick(slot.room.name)"
    >
      <TresMesh :position-z="0.02">
        <TresCircleGeometry :args="[0.8, 6]" />
        <TresMeshBasicMaterial :color="'#06060f'" :transparent="true" :opacity="0.95" />
      </TresMesh>
      <TresMesh :position-z="0.03">
        <TresRingGeometry :args="[0.8, 0.95, 6, 1]" />
        <TresMeshBasicMaterial :color="wingColor" :transparent="true" :opacity="0.85" />
      </TresMesh>
      <TresMesh :position="[0, 1.2, 0.04]">
        <TresPlaneGeometry :args="[1.8, 0.45]" />
        <TresMeshBasicMaterial :map="getRoomLabel(slot.room.name, slot.room.drawerCount)" :transparent="true" />
      </TresMesh>
    </TresGroup>

    <!-- Lighting -->
    <TresAmbientLight :intensity="0.5" :color="'#ffeecf'" />
    <TresPointLight
      v-for="(light, i) in ceilingLights"
      :key="`light-${i}`"
      :position="light.position"
      :intensity="0.9"
      :color="'#ffeecf'"
    />
  </TresGroup>
</template>
