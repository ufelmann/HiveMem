<script setup lang="ts">
import { computed, onBeforeUnmount } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'
import { makeStoneFloorTextureWithRepeat, makeHexWallTextureWithRepeat } from '../composables/useTextures'
import { hexWallPositions } from '../composables/hexMath'

const PI = Math.PI

const store = useNavigationStore()

const R = 6
const H = 5

const floorTexture = makeStoneFloorTextureWithRepeat(3, 3)
const wallTexture  = makeHexWallTextureWithRepeat(2, 2)

const hexSet = hexWallPositions(R)

const wingColor = computed(() => store.wingObj?.color ?? '#00BFFF')
const halls = computed(() => store.wingObj?.halls ?? [])

const labelTextures = new Map<string, THREE.CanvasTexture>()
function buildHallLabelCanvas(hall: string, count: number): HTMLCanvasElement {
  const W = 1024, Hc = 220
  const c = document.createElement('canvas'); c.width = W; c.height = Hc
  const ctx = c.getContext('2d')!
  ctx.clearRect(0, 0, W, Hc)
  ctx.fillStyle = 'rgba(10,10,26,0.92)'
  ctx.strokeStyle = 'rgba(0,191,255,0.85)'
  ctx.lineWidth = 6
  ctx.beginPath(); ctx.roundRect(10, 10, W - 20, Hc - 20, 22); ctx.fill(); ctx.stroke()
  ctx.fillStyle = '#00BFFF'
  ctx.font = 'bold 96px "Segoe UI", Arial, sans-serif'
  ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
  ctx.fillText(hall, W / 2, Hc / 2 - 22)
  ctx.fillStyle = '#9eb6d9'
  ctx.font = '44px "Segoe UI", Arial, sans-serif'
  ctx.fillText(`${count} drawers`, W / 2, Hc / 2 + 52)
  return c
}
function getHallLabel(hall: string, count: number): THREE.CanvasTexture {
  const key = `${hall}|${count}`
  const cached = labelTextures.get(key)
  if (cached) return cached
  const tex = new THREE.CanvasTexture(buildHallLabelCanvas(hall, count))
  tex.needsUpdate = true
  labelTextures.set(key, tex)
  return tex
}
onBeforeUnmount(() => {
  for (const t of labelTextures.values()) t.dispose()
  labelTextures.clear()
})

const wallAssignments = computed(() =>
  hexSet.walls.map((wall, i) => {
    const hall = halls.value[i] ?? null
    return { wall, hall }
  })
)

function onPortalClick(name: string) {
  if (store.isTransitioning) return
  store.enterHall(name)
}
</script>

<template>
  <TresGroup>
    <!-- Floor (hex platform) -->
    <TresMesh :rotation-y="PI / 6" :position-y="0.05">
      <TresCylinderGeometry :args="[R, R, 0.1, 6]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="floorTexture" :roughness="0.85" />
    </TresMesh>

    <!-- Ceiling -->
    <TresMesh :rotation-y="PI / 6" :position-y="H">
      <TresCylinderGeometry :args="[R, R, 0.05, 6]" />
      <TresMeshStandardMaterial :color="'#06060f'" :emissive="'#ffd17a'" :emissive-intensity="0.1" />
    </TresMesh>

    <!-- 6 wall panels -->
    <TresGroup v-for="wa in wallAssignments" :key="wa.wall.index">
      <TresMesh
        :position="[wa.wall.centerX, H / 2, wa.wall.centerZ]"
        :rotation-y="wa.wall.rotationY"
      >
        <TresPlaneGeometry :args="[wa.wall.edgeLength, H]" />
        <TresMeshStandardMaterial :color="'#ffffff'" :map="wallTexture" :side="THREE.DoubleSide" />
      </TresMesh>

      <TresGroup
        v-if="wa.hall"
        :position="[wa.wall.centerX * 0.97, H / 2, wa.wall.centerZ * 0.97]"
        :rotation-y="wa.wall.rotationY"
        @click="onPortalClick(wa.hall!.name)"
      >
        <TresMesh :position-z="0.02">
          <TresCircleGeometry :args="[1.6, 6]" />
          <TresMeshBasicMaterial :color="'#06060f'" :transparent="true" :opacity="0.95" />
        </TresMesh>
        <TresMesh :position-z="0.03">
          <TresRingGeometry :args="[1.6, 1.82, 6, 1]" />
          <TresMeshBasicMaterial :color="wingColor" :transparent="true" :opacity="0.85" />
        </TresMesh>
        <TresMesh :position="[0, 1.75, 0.04]">
          <TresPlaneGeometry :args="[3.0, 0.7]" />
          <TresMeshBasicMaterial :map="getHallLabel(wa.hall!.name, wa.hall!.drawerCount)" :transparent="true" />
        </TresMesh>
      </TresGroup>
    </TresGroup>

    <!-- Reactor beam -->
    <TresMesh :position="[0, H / 2, 0]">
      <TresCylinderGeometry :args="[0.08, 0.08, H, 16]" />
      <TresMeshBasicMaterial :color="'#00BFFF'" :transparent="true" :opacity="0.55" />
    </TresMesh>

    <!-- Lighting -->
    <TresAmbientLight :intensity="0.55" :color="'#ffeecf'" />
    <TresPointLight :position="[0, H - 0.3, 0]" :intensity="1.3" :color="'#ffeecf'" />
    <template v-for="wa in wallAssignments" :key="`light-${wa.wall.index}`">
      <TresPointLight
        v-if="wa.hall"
        :position="[wa.wall.centerX * 0.5, 1.8, wa.wall.centerZ * 0.5]"
        :intensity="0.35"
        :color="wingColor"
      />
    </template>
  </TresGroup>
</template>
