<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'
import { makeStoneFloorTextureWithRepeat, makeHexWallTextureWithRepeat } from '../composables/useTextures'

const PI = Math.PI

const store = useNavigationStore()

const R = 6

const floorTexture = makeStoneFloorTextureWithRepeat(3, 3)
const domeTexture  = makeHexWallTextureWithRepeat(8, 4)

const wingColor = computed(() => store.wingObj?.color ?? '#00BFFF')
const rooms = computed(() => store.hallObj?.rooms ?? [])

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

// Each room portal sits on the equator ring, evenly distributed around the sphere
const portalSlots = computed(() =>
  rooms.value.map((room: any, i: number) => {
    const N = rooms.value.length
    const theta = (i / Math.max(N, 1)) * 2 * PI + PI / 6
    const px = R * Math.cos(theta)
    const pz = R * Math.sin(theta)
    const py = 2.0
    // Rotation-y so the portal's +Z normal points toward the origin
    const rotY = -theta + PI / 2
    return {
      room,
      position: [px, py, pz] as [number, number, number],
      rotationY: rotY,
    }
  })
)

function onPortalClick(name: string) {
  if (store.isTransitioning) return
  store.enterRoom(name)
}
</script>

<template>
  <TresGroup>
    <!-- Inner dome sphere — BackSide so we see the inside -->
    <TresMesh>
      <TresSphereGeometry :args="[R, 32, 16]" />
      <TresMeshStandardMaterial
        :color="'#ffffff'"
        :map="domeTexture"
        :roughness="0.7"
        :side="THREE.BackSide"
      />
    </TresMesh>

    <!-- Floor disk -->
    <TresMesh :position-y="0">
      <TresCylinderGeometry :args="[R, R, 0.1, 48]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="floorTexture" :roughness="0.85" />
    </TresMesh>

    <!-- Reactor beam — thin cyan cylinder from y=0 to y=R -->
    <TresMesh :position="[0, R / 2, 0]">
      <TresCylinderGeometry :args="[0.08, 0.08, R, 16]" />
      <TresMeshBasicMaterial :color="'#00BFFF'" :transparent="true" :opacity="0.55" />
    </TresMesh>

    <!-- Room portals on equator ring -->
    <TresGroup
      v-for="slot in portalSlots"
      :key="slot.room.name"
      :position="slot.position"
      :rotation-y="slot.rotationY"
      @click="onPortalClick(slot.room.name)"
    >
      <!-- Dark inset hex -->
      <TresMesh :position-z="0.02">
        <TresCircleGeometry :args="[0.9, 6]" />
        <TresMeshBasicMaterial :color="'#06060f'" :transparent="true" :opacity="0.95" />
      </TresMesh>
      <!-- Cyan glow rim -->
      <TresMesh :position-z="0.03">
        <TresRingGeometry :args="[0.9, 1.05, 6, 1]" />
        <TresMeshBasicMaterial :color="wingColor" :transparent="true" :opacity="0.85" />
      </TresMesh>
      <!-- Room label -->
      <TresMesh :position="[0, 1.25, 0.04]">
        <TresPlaneGeometry :args="[1.8, 0.45]" />
        <TresMeshBasicMaterial :map="getRoomLabel(slot.room.name, slot.room.drawerCount)" :transparent="true" />
      </TresMesh>
    </TresGroup>

    <!-- Lighting -->
    <TresAmbientLight :intensity="0.55" :color="'#ffeecf'" />
    <TresPointLight :position="[0, R - 0.3, 0]" :intensity="1.3" :color="'#ffeecf'" />
    <template v-for="slot in portalSlots" :key="`light-${slot.room.name}`">
      <TresPointLight
        :position="[slot.position[0] * 0.5, 1.8, slot.position[2] * 0.5]"
        :intensity="0.35"
        :color="wingColor"
      />
    </template>
  </TresGroup>
</template>
