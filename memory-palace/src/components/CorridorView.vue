<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'
import { makeHexWallTextureWithRepeat, makeStoneFloorTextureWithRepeat } from '../composables/useTextures'

const PI = Math.PI

const store = useNavigationStore()

// ─── Door label canvas textures ─────────────────────────────────────────────

function buildDoorLabelCanvas(text: string): HTMLCanvasElement {
  const W = 512, H = 128
  const canvas = document.createElement('canvas')
  canvas.width = W; canvas.height = H
  const ctx = canvas.getContext('2d')!
  ctx.clearRect(0, 0, W, H)
  // Dark rounded-rect background with cyan glow border
  ctx.fillStyle = 'rgba(10,10,26,0.9)'
  ctx.strokeStyle = 'rgba(0,191,255,0.75)'
  ctx.lineWidth = 4
  ctx.beginPath()
  ctx.roundRect(8, 8, W - 16, H - 16, 16)
  ctx.fill(); ctx.stroke()
  // Centered cyan text
  ctx.fillStyle = '#00BFFF'
  ctx.font = 'bold 56px "Segoe UI", Arial, sans-serif'
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
  ctx.fillText(text, W / 2, H / 2)
  return canvas
}

const labelTextures = new Map<string, THREE.CanvasTexture>()

function getDoorLabel(name: string): THREE.CanvasTexture {
  const cached = labelTextures.get(name)
  if (cached) return cached
  const tex = new THREE.CanvasTexture(buildDoorLabelCanvas(name))
  tex.needsUpdate = true
  labelTextures.set(name, tex)
  return tex
}

onBeforeUnmount(() => {
  for (const tex of labelTextures.values()) tex.dispose()
  labelTextures.clear()
})

const floorTexture = makeStoneFloorTextureWithRepeat(4, 1)
const wallTexture = makeHexWallTextureWithRepeat(8, 2)

const rooms = computed(() => store.hallObj?.rooms ?? [])
const halls = computed(() => store.wingObj?.halls ?? [])
const wingColor = computed(() => store.wingObj?.color ?? '#00BFFF')

const CORRIDOR_LENGTH = 16
const CORRIDOR_WIDTH = 4
const CORRIDOR_HEIGHT = 3.2

const doorPositions = computed(() => {
  const n = rooms.value.length
  return rooms.value.map((room: any, i: number) => {
    const side = i % 2 === 0 ? -1 : 1
    const along = -CORRIDOR_LENGTH / 2 + ((i + 1) * CORRIDOR_LENGTH) / (n + 1)
    const wallZ = side * (CORRIDOR_WIDTH / 2 - 0.05)
    return {
      room,
      position: [along, 1.3, wallZ] as [number, number, number],
      rotationY: side === -1 ? 0 : Math.PI,
      side,
    }
  })
})

const hallSwitcherPositions = computed(() =>
  halls.value.map((hall: any, i: number) => ({
    hall,
    position: [-CORRIDOR_LENGTH / 2 + 1 + i * 2.4, 0.15, 0] as [number, number, number],
  })),
)

function onDoorClick(name: string) {
  if (store.isTransitioning) return
  store.enterRoom(name)
}

function onHallClick(name: string) {
  if (store.isTransitioning) return
  store.enterHall(name)
}
</script>

<template>
  <TresGroup>
    <!-- Floor -->
    <TresMesh :rotation-x="-PI / 2">
      <TresPlaneGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_WIDTH]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :roughness="0.5" :metalness="0.3" :map="floorTexture" />
    </TresMesh>
    <!-- Ceiling -->
    <TresMesh :rotation-x="PI / 2" :position-y="CORRIDOR_HEIGHT">
      <TresPlaneGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_WIDTH]" />
      <TresMeshStandardMaterial :color="'#0a0a1a'" :roughness="0.8" />
    </TresMesh>
    <!-- Left wall -->
    <TresMesh :position="[0, CORRIDOR_HEIGHT / 2, -CORRIDOR_WIDTH / 2]">
      <TresBoxGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_HEIGHT, 0.1]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :emissive="'#000000'" :roughness="0.7" :map="wallTexture" />
    </TresMesh>
    <!-- Right wall -->
    <TresMesh :position="[0, CORRIDOR_HEIGHT / 2, CORRIDOR_WIDTH / 2]">
      <TresBoxGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_HEIGHT, 0.1]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :emissive="'#000000'" :roughness="0.7" :map="wallTexture" />
    </TresMesh>

    <!-- Doors -->
    <TresGroup v-for="door in doorPositions" :key="door.room.name"
               :position="door.position" :rotation-y="door.rotationY"
               @click="onDoorClick(door.room.name)">
      <TresMesh>
        <TresBoxGeometry :args="[1.2, 2.2, 0.08]" />
        <TresMeshStandardMaterial :color="'#2a2520'" :emissive="wingColor" :emissive-intensity="0.12" :roughness="0.3" />
      </TresMesh>
      <TresMesh :position-y="1.2">
        <TresBoxGeometry :args="[1.3, 0.1, 0.1]" />
        <TresMeshBasicMaterial :color="wingColor" />
      </TresMesh>
      <!-- Door label above door -->
      <TresMesh :position="[0, 1.55, 0.06]">
        <TresPlaneGeometry :args="[1.4, 0.35]" />
        <TresMeshBasicMaterial :map="getDoorLabel(door.room.name)" :transparent="true" />
      </TresMesh>
    </TresGroup>

    <!-- Hall switcher pads at corridor start -->
    <TresGroup v-for="hp in hallSwitcherPositions" :key="hp.hall.name"
               :position="hp.position" @click="onHallClick(hp.hall.name)">
      <TresMesh>
        <TresBoxGeometry :args="[2, 0.15, 0.8]" />
        <TresMeshStandardMaterial
          :color="'#1c1c2e'"
          :emissive="hp.hall.name === store.currentHall ? wingColor : '#333340'"
          :emissive-intensity="hp.hall.name === store.currentHall ? 0.6 : 0.15" />
      </TresMesh>
    </TresGroup>

    <!-- Lights -->
    <TresAmbientLight :intensity="0.7" :color="'#ffeecf'" />
    <TresPointLight :position="[-5, 2.8, 0]" :intensity="1.5" :color="'#ffeecf'" />
    <TresPointLight :position="[5, 2.8, 0]" :intensity="1.5" :color="'#ffeecf'" />
    <TresPointLight :position="[0, 2.8, 0]" :intensity="0.4" :color="wingColor" />
  </TresGroup>
</template>
