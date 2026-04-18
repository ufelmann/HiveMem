<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Drawer } from '../types/palace'
import { useNavigationStore } from '../stores/navigation'

const props = defineProps<{
  drawer: Drawer
  position: [number, number, number]
  rotationY?: number
}>()

const store = useNavigationStore()
const hovered = ref(false)
const canHover = typeof window !== 'undefined' && window.matchMedia('(hover: hover)').matches

const sizeY = computed(() => 0.3 + props.drawer.importance * 0.08)
const baseIntensity = computed(() => 0.2 + props.drawer.importance * 0.12)

const emissiveColor = computed(() => (props.drawer.status === 'pending' ? '#FF8C00' : '#00BFFF'))

const intensity = ref(baseIntensity.value)

let frame = 0
function onBeforeRender() {
  frame++
  if (props.drawer.status === 'pending') {
    const t = frame * 0.05
    intensity.value = 0.5 + 0.3 * Math.sin(t * 2 * Math.PI * 0.5)
  } else if (hovered.value && canHover) {
    intensity.value = baseIntensity.value * 1.6
  } else {
    intensity.value = baseIntensity.value
  }
}

function onClick(e: any) {
  e.stopPropagation?.()
  if (store.isTransitioning) return
  store.selectDrawer(props.drawer.id)
}
</script>

<template>
  <TresGroup :position="position" :rotation-y="rotationY ?? 0" @click="onClick"
             @pointer-over="hovered = true" @pointer-leave="hovered = false">
    <TresMesh :scale-y="sizeY" @before-render="onBeforeRender">
      <TresBoxGeometry :args="[0.6, 1, 0.5]" />
      <TresMeshStandardMaterial
        :color="'#1c1c2e'"
        :emissive="emissiveColor"
        :emissive-intensity="intensity"
        :roughness="0.4"
        :metalness="0.3" />
    </TresMesh>
    <TresMesh :scale-y="sizeY" :position-z="0.001">
      <TresBoxGeometry :args="[0.62, 1.02, 0.51]" />
      <TresMeshBasicMaterial :color="emissiveColor" :wireframe="true" :transparent="true" :opacity="0.35" />
    </TresMesh>
  </TresGroup>
</template>
