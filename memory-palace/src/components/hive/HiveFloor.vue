<script setup lang="ts">
import { useDisplay } from 'vuetify'

const { mobile } = useDisplay()
const PI = Math.PI
</script>

<template>
  <TresMesh :rotation-x="-PI / 2" :position-y="-3">
    <TresPlaneGeometry :args="[40, 40]" />
    <TresMeshStandardMaterial
      :color="'#0a0a12'"
      :metalness="mobile ? 0 : 0.6"
      :roughness="mobile ? 0.9 : 0.1"
    />
  </TresMesh>
</template>
