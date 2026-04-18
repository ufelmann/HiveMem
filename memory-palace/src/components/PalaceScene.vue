<script setup lang="ts">
import { TresCanvas } from '@tresjs/core'
import { OrbitControls } from '@tresjs/cientos'
import { computed, shallowRef, watch } from 'vue'
import { useNavigationStore } from '../stores/navigation'
import BuildingView from './BuildingView.vue'
import WingInteriorView from './WingInteriorView.vue'
import HallTunnelView from './HallTunnelView.vue'
import RoomCellView from './RoomCellView.vue'
import CameraController from './CameraController.vue'
import DrawerCardStack from './DrawerCardStack.vue'

const store = useNavigationStore()
const R = 6 // sphere radius used by HallTunnelView

// Template ref to capture OrbitControls instance and expose to CameraController via window
const orbitRef = shallowRef<any>(null)
watch(orbitRef, (v) => {
  // Cientos wraps the three OrbitControls; the `.value` on the ref IS the instance,
  // but Cientos exposes it via `.instance` or directly. Try both.
  const instance = v?.instance ?? v?.value ?? v
  if (instance) {
    // @ts-expect-error intentional globalto bypass missing useTresContext.controls
    window.__palaceOrbit = instance
  }
}, { immediate: true })

interface OrbitOpts {
  enableZoom?: boolean
  enablePan?: boolean
  enableRotate?: boolean
  minDistance?: number
  maxDistance?: number
  minPolarAngle?: number
  maxPolarAngle?: number
}

const orbitOptions = computed<OrbitOpts>(() => {
  switch (store.level) {
    case 'building':
      return { enableZoom: true, enablePan: true, enableRotate: true, minDistance: 8, maxDistance: 40, minPolarAngle: 0.1, maxPolarAngle: 1.4 }
    case 'wing':
      return { enableZoom: true, enablePan: true, enableRotate: true, minDistance: 1.2, maxDistance: 14, minPolarAngle: 0.15, maxPolarAngle: 1.55 }
    case 'hall':
      return { enableZoom: true, enablePan: true, enableRotate: true, minDistance: 0.5, maxDistance: R - 0.5, minPolarAngle: 0.2, maxPolarAngle: 1.5 }
    case 'room':
      return { enableZoom: true, enablePan: true, enableRotate: true, minDistance: 1.5, maxDistance: 8, minPolarAngle: 0.2, maxPolarAngle: 1.55 }
    case 'drawer':
      return { enableZoom: true, enablePan: true, enableRotate: false, minDistance: 1.5, maxDistance: 8 }
  }
  return {}
})
</script>

<template>
  <TresCanvas clear-color="#0a0a1a" window-size>
    <TresPerspectiveCamera :args="[60, 1, 0.1, 100]" :position="[0, 8, 20]" />
    <OrbitControls
      ref="orbitRef"
      :enable-damping="true"
      :screen-space-panning="true"
      :enable-zoom="orbitOptions.enableZoom ?? true"
      :enable-pan="orbitOptions.enablePan ?? false"
      :enable-rotate="orbitOptions.enableRotate ?? true"
      :min-distance="orbitOptions.minDistance ?? 1"
      :max-distance="orbitOptions.maxDistance ?? 50"
      :min-polar-angle="orbitOptions.minPolarAngle ?? 0.05"
      :max-polar-angle="orbitOptions.maxPolarAngle ?? 1.6"
    />
    <CameraController />

    <BuildingView v-if="store.level === 'building'" />
    <WingInteriorView v-else-if="store.level === 'wing'" />
    <HallTunnelView v-else-if="store.level === 'hall'" />
    <RoomCellView v-else />

    <DrawerCardStack v-if="store.level === 'drawer' && store.selectedDrawer" />
  </TresCanvas>
</template>
