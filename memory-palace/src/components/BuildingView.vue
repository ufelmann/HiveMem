<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'
import { makeStoneFloorTextureWithRepeat, makeHexWallTextureWithRepeat } from '../composables/useTextures'

const store = useNavigationStore()

const PI = Math.PI

const CORE_RADIUS = 2.5
const CORE_HEIGHT = 0.4
const WING_RADIUS = 2.2
const WING_HEIGHT = 3.5
const ORBIT_RADIUS = 5.5

const groundTexture = makeStoneFloorTextureWithRepeat(10, 10)
const wingTexture = makeHexWallTextureWithRepeat(3, 2)

// Wing label canvas cache
const labelTextures = new Map<string, THREE.CanvasTexture>()

function buildWingLabelCanvas(wing: string, count: number): HTMLCanvasElement {
  const W = 512, H = 160
  const canvas = document.createElement('canvas')
  canvas.width = W; canvas.height = H
  const ctx = canvas.getContext('2d')!
  ctx.clearRect(0, 0, W, H)
  ctx.fillStyle = 'rgba(10,10,26,0.92)'
  ctx.strokeStyle = 'rgba(0,191,255,0.8)'
  ctx.lineWidth = 4
  ctx.beginPath(); ctx.roundRect(8, 8, W - 16, H - 16, 18); ctx.fill(); ctx.stroke()
  ctx.fillStyle = '#00BFFF'
  ctx.font = 'bold 54px "Segoe UI", Arial, sans-serif'
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
  ctx.fillText(wing, W / 2, H / 2 - 16)
  ctx.fillStyle = '#9eb6d9'
  ctx.font = '24px "Segoe UI", Arial, sans-serif'
  ctx.fillText(`${count} drawers`, W / 2, H / 2 + 28)
  return canvas
}

function getWingLabel(wing: string, count: number): THREE.CanvasTexture {
  const key = `${wing}|${count}`
  const cached = labelTextures.get(key)
  if (cached) return cached
  const tex = new THREE.CanvasTexture(buildWingLabelCanvas(wing, count))
  tex.needsUpdate = true
  labelTextures.set(key, tex)
  return tex
}

onBeforeUnmount(() => {
  for (const tex of labelTextures.values()) tex.dispose()
  labelTextures.clear()
})

const wingSlots = computed(() =>
  store.palace.wings.map((wing, i) => {
    const n = store.palace.wings.length || 1
    const theta = (i / n) * PI * 2 + PI / 6
    const cx = Math.cos(theta) * ORBIT_RADIUS
    const cz = Math.sin(theta) * ORBIT_RADIUS
    return {
      wing,
      position: [cx, WING_HEIGHT / 2, cz] as [number, number, number],
      topPosition: [cx, WING_HEIGHT + 0.8, cz] as [number, number, number],
      bridgePosition: [cx / 2, CORE_HEIGHT + 0.08, cz / 2] as [number, number, number],
      bridgeAngle: theta,
      theta,
    }
  })
)

function onWingClick(name: string) {
  if (store.isTransitioning) return
  store.enterWing(name)
}
</script>

<template>
  <TresGroup>
    <!-- Ground disk -->
    <TresMesh :rotation-x="-PI / 2" :position-y="0">
      <TresCircleGeometry :args="[24, 64]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="groundTexture" :roughness="0.85" :metalness="0.05" />
    </TresMesh>

    <!-- Central core (flat hexagonal platform, flat-top) -->
    <TresMesh :position="[0, CORE_HEIGHT / 2, 0]" :rotation-y="PI / 6">
      <TresCylinderGeometry :args="[CORE_RADIUS, CORE_RADIUS, CORE_HEIGHT, 6]" />
      <TresMeshStandardMaterial
        :color="'#2a2520'"
        :emissive="'#ffd17a'"
        :emissive-intensity="0.35"
        :roughness="0.4"
        :metalness="0.35" />
    </TresMesh>

    <!-- Reactor beam at centre -->
    <TresMesh :position="[0, 3, 0]">
      <TresCylinderGeometry :args="[0.1, 0.1, 6, 16]" />
      <TresMeshBasicMaterial :color="'#00BFFF'" :transparent="true" :opacity="0.6" />
    </TresMesh>

    <!-- Bridges from core to each wing -->
    <TresGroup
      v-for="slot in wingSlots"
      :key="`bridge-${slot.wing.name}`"
      :position="slot.bridgePosition"
      :rotation-y="-slot.bridgeAngle"
    >
      <TresMesh>
        <TresBoxGeometry :args="[ORBIT_RADIUS - 0.2, 0.15, 0.8]" />
        <TresMeshStandardMaterial
          :color="'#1c1c2e'"
          :emissive="slot.wing.color"
          :emissive-intensity="0.25"
          :roughness="0.3" />
      </TresMesh>
    </TresGroup>

    <!-- Wings -->
    <TresGroup
      v-for="slot in wingSlots"
      :key="slot.wing.name"
      :position="slot.position"
      :rotation-y="PI / 6"
      @click="onWingClick(slot.wing.name)"
    >
      <TresMesh>
        <TresCylinderGeometry :args="[WING_RADIUS, WING_RADIUS, WING_HEIGHT, 6]" />
        <TresMeshStandardMaterial
          :color="'#ffffff'"
          :emissive="slot.wing.color"
          :emissive-intensity="0.35"
          :map="wingTexture"
          :roughness="0.55"
          :metalness="0.1" />
      </TresMesh>
      <!-- Top glow ring -->
      <TresMesh :position-y="WING_HEIGHT / 2 + 0.01" :rotation-x="-PI / 2">
        <TresRingGeometry :args="[WING_RADIUS * 0.92, WING_RADIUS * 1.02, 32]" />
        <TresMeshBasicMaterial :color="slot.wing.color" :transparent="true" :opacity="0.85" />
      </TresMesh>
    </TresGroup>

    <!-- Wing labels -->
    <TresMesh
      v-for="slot in wingSlots"
      :key="`label-${slot.wing.name}`"
      :position="slot.topPosition"
    >
      <TresPlaneGeometry :args="[2.6, 0.8]" />
      <TresMeshBasicMaterial :map="getWingLabel(slot.wing.name, slot.wing.drawerCount)" :transparent="true" />
    </TresMesh>

    <!-- Per-wing point lights -->
    <TresPointLight
      v-for="slot in wingSlots"
      :key="`light-${slot.wing.name}`"
      :position="[slot.position[0], 5, slot.position[2]]"
      :intensity="0.5"
      :color="slot.wing.color" />

    <!-- Global lights -->
    <TresAmbientLight :intensity="0.45" :color="'#ffeecf'" />
    <TresPointLight :position="[0, 8, 0]" :intensity="1.6" :color="'#ffeecf'" />
  </TresGroup>
</template>
