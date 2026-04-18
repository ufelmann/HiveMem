<script setup lang="ts">
import { TresCanvas } from '@tresjs/core'
import { OrbitControls } from '@tresjs/cientos'
import { computed } from 'vue'
import { useNavigationStore } from '../stores/navigation'
import BuildingView from './BuildingView.vue'
import CorridorView from './CorridorView.vue'
import RoomView from './RoomView.vue'
import DrawerSheet from './DrawerSheet.vue'
import CameraController from './CameraController.vue'

const store = useNavigationStore()

const orbitOptions = computed(() => {
  switch (store.level) {
    case 'building': return { enableZoom: true, enablePan: false, minDistance: 12, maxDistance: 32, maxPolarAngle: 1.4 }
    case 'corridor': return { enableZoom: true, enablePan: true, enableRotate: true, minPolarAngle: 0.2, maxPolarAngle: 1.7 }
    case 'room': return { enableZoom: true, enablePan: true, enableRotate: true, minPolarAngle: 0.1, maxPolarAngle: 1.6, minDistance: 1.5, maxDistance: 12 }
    case 'drawer': return { enableZoom: true, enablePan: true, enableRotate: false, minDistance: 1.5, maxDistance: 8 }
  }
})
</script>

<template>
  <TresCanvas clear-color="#0a0a1a" window-size>
    <TresPerspectiveCamera :args="[60, 1, 0.1, 100]" :position="[0, 8, 20]" />
    <OrbitControls
      :enable-damping="true"
      :enable-zoom="orbitOptions.enableZoom"
      :enable-pan="orbitOptions.enablePan"
      :enable-rotate="orbitOptions.enableRotate ?? true"
      :min-distance="orbitOptions.minDistance ?? 1"
      :max-distance="orbitOptions.maxDistance ?? 50"
      :min-polar-angle="orbitOptions.minPolarAngle ?? 0.05"
      :max-polar-angle="orbitOptions.maxPolarAngle ?? 1.5"
      :screen-space-panning="true" />
    <CameraController />

    <BuildingView v-if="store.level === 'building'" />
    <CorridorView v-else-if="store.level === 'corridor'" />
    <RoomView v-else />
    <DrawerSheet v-if="store.level === 'drawer' && store.selectedDrawer" />
  </TresCanvas>
</template>
