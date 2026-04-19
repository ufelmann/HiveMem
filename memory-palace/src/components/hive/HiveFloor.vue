<script setup lang="ts">
const isMobile = typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches
const PI = Math.PI
</script>

<template>
  <TresMesh :rotation-x="-PI / 2" :position-y="-3">
    <TresPlaneGeometry :args="[40, 40]" />
    <TresMeshStandardMaterial
      :color="'#0a0a12'"
      :metalness="isMobile ? 0 : 0.6"
      :roughness="isMobile ? 0.9 : 0.1"
    />
  </TresMesh>
</template>
