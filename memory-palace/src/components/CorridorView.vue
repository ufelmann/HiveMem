<script setup lang="ts">
import { computed } from 'vue'
import { useNavigationStore } from '../stores/navigation'

const PI = Math.PI

const store = useNavigationStore()

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
    return {
      room,
      position: [along, 1.3, side * (CORRIDOR_WIDTH / 2 - 0.15)] as [number, number, number],
      rotationY: side === -1 ? PI / 2 : -PI / 2,
      side,
    }
  })
})

const hallSwitcherPositions = computed(() =>
  halls.value.map((hall: any, i: number) => ({
    hall,
    position: [-8 + i * 3, 3.4, 0] as [number, number, number],
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
      <TresMeshStandardMaterial :color="'#1a1a2e'" :roughness="0.5" :metalness="0.3" />
    </TresMesh>
    <!-- Ceiling -->
    <TresMesh :rotation-x="PI / 2" :position-y="CORRIDOR_HEIGHT">
      <TresPlaneGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_WIDTH]" />
      <TresMeshStandardMaterial :color="'#0a0a1a'" :roughness="0.8" />
    </TresMesh>
    <!-- Left wall -->
    <TresMesh :position="[0, CORRIDOR_HEIGHT / 2, -CORRIDOR_WIDTH / 2]">
      <TresBoxGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_HEIGHT, 0.1]" />
      <TresMeshStandardMaterial :color="'#2a2520'" :emissive="wingColor" :emissive-intensity="0.08" :roughness="0.7" />
    </TresMesh>
    <!-- Right wall -->
    <TresMesh :position="[0, CORRIDOR_HEIGHT / 2, CORRIDOR_WIDTH / 2]">
      <TresBoxGeometry :args="[CORRIDOR_LENGTH, CORRIDOR_HEIGHT, 0.1]" />
      <TresMeshStandardMaterial :color="'#2a2520'" :emissive="wingColor" :emissive-intensity="0.08" :roughness="0.7" />
    </TresMesh>

    <!-- Doors -->
    <TresGroup v-for="door in doorPositions" :key="door.room.name"
               :position="door.position" :rotation-y="door.rotationY"
               @click="onDoorClick(door.room.name)">
      <TresMesh>
        <TresBoxGeometry :args="[1.6, 2.6, 0.1]" />
        <TresMeshStandardMaterial :color="'#1c1c2e'" :emissive="wingColor" :emissive-intensity="0.45" :roughness="0.3" />
      </TresMesh>
      <TresMesh :position-y="1.6">
        <TresBoxGeometry :args="[1.7, 0.1, 0.12]" />
        <TresMeshBasicMaterial :color="wingColor" />
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
    <TresAmbientLight :intensity="0.15" />
    <TresPointLight :position="[-5, 2.8, 0]" :intensity="1.1" :color="wingColor" />
    <TresPointLight :position="[5, 2.8, 0]" :intensity="1.1" :color="wingColor" />
  </TresGroup>
</template>
