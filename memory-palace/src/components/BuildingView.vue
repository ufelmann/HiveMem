<script setup lang="ts">
import { computed } from 'vue'
import { useNavigationStore } from '../stores/navigation'
import { makeStoneFloorTextureWithRepeat } from '../composables/useTextures'

const store = useNavigationStore()

const PI = Math.PI

const floorTexture = makeStoneFloorTextureWithRepeat(8, 8)

const wingPositions = computed(() =>
  store.palace.wings.map((wing, i) => {
    const spacing = 8
    const offset = (i - (store.palace.wings.length - 1) / 2) * spacing
    return { wing, position: [offset, 2, 0] as [number, number, number] }
  })
)

function onWingClick(name: string) {
  if (store.isTransitioning) return
  store.enterWing(name)
}
</script>

<template>
  <TresGroup>
    <!-- Ground -->
    <TresMesh :rotation-x="-PI / 2" :position-y="0">
      <TresPlaneGeometry :args="[40, 40]" />
      <TresMeshStandardMaterial :color="'#1a1a2e'" :roughness="0.8" :metalness="0.1" :map="floorTexture" />
    </TresMesh>

    <!-- Wings -->
    <TresGroup v-for="{ wing, position } in wingPositions" :key="wing.name"
               :position="position" @click="onWingClick(wing.name)">
      <TresMesh>
        <TresBoxGeometry :args="[6, 4, 10]" />
        <TresMeshStandardMaterial :color="'#2a2520'" :emissive="wing.color" :emissive-intensity="0.25" :roughness="0.6" />
      </TresMesh>
      <TresMesh :position-y="2.5">
        <TresBoxGeometry :args="[6.2, 0.2, 10.2]" />
        <TresMeshBasicMaterial :color="wing.color" :transparent="true" :opacity="0.6" />
      </TresMesh>
    </TresGroup>

    <!-- Ambient + directional lights -->
    <TresAmbientLight :intensity="0.2" />
    <TresPointLight :position="[0, 12, 0]" :intensity="1.2" :color="'#ffffff'" />
    <TresPointLight :position="[-12, 6, 8]" :intensity="0.6" :color="'#00BFFF'" />
    <TresPointLight :position="[12, 6, 8]" :intensity="0.6" :color="'#00FF88'" />
  </TresGroup>
</template>
