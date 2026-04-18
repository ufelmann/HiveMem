<script setup lang="ts">
import { computed } from 'vue'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'
import { makeStoneFloorTextureWithRepeat, makeHexWallTextureWithRepeat } from '../composables/useTextures'
import { hexWallPositions } from '../composables/hexMath'
import DrawerMesh from './DrawerMesh.vue'

const PI = Math.PI

const store = useNavigationStore()

const R_C = 4
const H_C = 3.5

const hexSet = hexWallPositions(R_C)

const floorTexture = makeStoneFloorTextureWithRepeat(2, 2)
const wallTexture  = makeHexWallTextureWithRepeat(2, 1.5)

const wingColor = computed(() => store.wingObj?.color ?? '#00BFFF')

const drawerSlots = computed(() => {
  const drawers = store.visibleDrawersInRoom
  const slots: Array<{ drawer: any; position: [number, number, number]; rotationY: number }> = []
  const ROW_YS = [1.0, 1.9, 2.8]
  const PULL_IN = 0.35

  drawers.forEach((drawer: any, i: number) => {
    const wallIdx = (i % 5) + 1
    const row = Math.floor(i / 5)
    if (row >= ROW_YS.length) return
    const wall = hexSet.walls[wallIdx]
    const a = hexSet.apothem - PULL_IN
    const x = Math.cos(wall.angle) * a
    const z = Math.sin(wall.angle) * a
    const y = ROW_YS[row]
    slots.push({ drawer, position: [x, y, z], rotationY: wall.rotationY })
  })
  return slots
})
</script>

<template>
  <TresGroup>
    <!-- Floor -->
    <TresMesh :rotation-y="PI / 6" :position-y="0.05">
      <TresCylinderGeometry :args="[R_C, R_C, 0.1, 6]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="floorTexture" :roughness="0.7" />
    </TresMesh>

    <!-- Ceiling -->
    <TresMesh :rotation-y="PI / 6" :position-y="H_C">
      <TresCylinderGeometry :args="[R_C, R_C, 0.05, 6]" />
      <TresMeshStandardMaterial :color="'#06060f'" :emissive="wingColor" :emissive-intensity="0.08" />
    </TresMesh>

    <!-- 6 inner walls -->
    <TresMesh
      v-for="wall in hexSet.walls"
      :key="`wall-${wall.index}`"
      :position="[wall.centerX, H_C / 2, wall.centerZ]"
      :rotation-y="wall.rotationY"
    >
      <TresPlaneGeometry :args="[wall.edgeLength, H_C]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :map="wallTexture" :side="THREE.DoubleSide" />
    </TresMesh>

    <!-- Drawers -->
    <DrawerMesh
      v-for="slot in drawerSlots"
      :key="slot.drawer.id"
      :drawer="slot.drawer"
      :position="slot.position"
      :rotation-y="slot.rotationY"
    />

    <!-- Lighting -->
    <TresAmbientLight :intensity="0.55" :color="'#ffeecf'" />
    <TresPointLight :position="[0, H_C - 0.3, 0]" :intensity="1.2" :color="'#ffeecf'" />
    <TresPointLight :position="[0, 1.8, -2]" :intensity="0.4" :color="wingColor" />
  </TresGroup>
</template>
