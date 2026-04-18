<script setup lang="ts">
import { computed } from 'vue'
import { useNavigationStore } from '../stores/navigation'
import DrawerMesh from './DrawerMesh.vue'
import TunnelLines from './TunnelLines.vue'
import { makeHexWallTextureWithRepeat, makeStoneFloorTextureWithRepeat } from '../composables/useTextures'

const PI = Math.PI

const store = useNavigationStore()

const floorTexture = makeStoneFloorTextureWithRepeat(2, 2)
const wallTexture = makeHexWallTextureWithRepeat(3, 1.5)

const ROOM_W = 6
const ROOM_D = 6
const ROOM_H = 3

const drawerSlots = computed(() => {
  const drawers = store.visibleDrawersInRoom
  const slots: { drawer: any; position: [number, number, number]; rotationY: number; index: number }[] = []
  drawers.forEach((drawer: any, i: number) => {
    const half = Math.ceil(drawers.length / 2)
    const onBack = i < half
    if (onBack) {
      const col = i
      const x = -ROOM_W / 2 + 1 + col * 1.2
      slots.push({ drawer, position: [x, 1.3, -ROOM_D / 2 + 0.3], rotationY: 0, index: i })
    } else {
      const col = i - half
      const z = -ROOM_D / 2 + 1 + col * 1.2
      slots.push({ drawer, position: [-ROOM_W / 2 + 0.3, 1.3, z], rotationY: PI / 2, index: i })
    }
  })
  return slots
})

const drawerPositionById = computed(() => {
  const m = new Map<string, [number, number, number]>()
  drawerSlots.value.forEach((s) => m.set(s.drawer.id, s.position))
  return m
})

const tunnelSegments = computed(() => {
  const segs: { from: [number, number, number]; to: [number, number, number]; relation: string }[] = []
  const positions = drawerPositionById.value
  for (const { drawer } of drawerSlots.value) {
    for (const tunnel of drawer.tunnels) {
      const targetPos = positions.get(tunnel.targetId)
      const src = positions.get(drawer.id)
      if (!src || !targetPos) continue
      segs.push({ from: src, to: targetPos, relation: tunnel.relation })
    }
  }
  return segs
})

const wingColor = computed(() => store.wingObj?.color ?? '#00BFFF')
</script>

<template>
  <TresGroup>
    <!-- Floor -->
    <TresMesh :rotation-x="-PI / 2">
      <TresPlaneGeometry :args="[ROOM_W, ROOM_D]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :roughness="0.6" :metalness="0.2" :map="floorTexture" />
    </TresMesh>
    <!-- Ceiling -->
    <TresMesh :rotation-x="PI / 2" :position-y="ROOM_H">
      <TresPlaneGeometry :args="[ROOM_W, ROOM_D]" />
      <TresMeshStandardMaterial :color="'#0a0a1a'" :roughness="0.8" />
    </TresMesh>
    <!-- Back wall -->
    <TresMesh :position="[0, ROOM_H / 2, -ROOM_D / 2]">
      <TresPlaneGeometry :args="[ROOM_W, ROOM_H]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :emissive="'#000000'" :map="wallTexture" />
    </TresMesh>
    <!-- Left wall -->
    <TresMesh :rotation-y="PI / 2" :position="[-ROOM_W / 2, ROOM_H / 2, 0]">
      <TresPlaneGeometry :args="[ROOM_D, ROOM_H]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :emissive="'#000000'" :map="wallTexture" />
    </TresMesh>
    <!-- Right wall -->
    <TresMesh :rotation-y="-PI / 2" :position="[ROOM_W / 2, ROOM_H / 2, 0]">
      <TresPlaneGeometry :args="[ROOM_D, ROOM_H]" />
      <TresMeshStandardMaterial :color="'#ffffff'" :emissive="'#000000'" :map="wallTexture" />
    </TresMesh>

    <!-- Drawers -->
    <DrawerMesh v-for="slot in drawerSlots" :key="slot.drawer.id"
                :drawer="slot.drawer" :position="slot.position" :rotation-y="slot.rotationY" />

    <!-- Tunnels -->
    <TunnelLines :segments="tunnelSegments" />

    <!-- Lights -->
    <TresAmbientLight :intensity="0.55" :color="'#ffeecf'" />
    <TresPointLight :position="[0, ROOM_H - 0.2, 0]" :intensity="1.1" :color="'#ffeecf'" />
    <TresPointLight :position="[0, 2.2, 2]" :intensity="0.5" :color="wingColor" />
  </TresGroup>
</template>
