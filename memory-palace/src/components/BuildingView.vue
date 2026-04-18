<script setup lang="ts">
import * as THREE from 'three'
import HiveSphere from './hive/HiveSphere.vue'
import CyberBees from './hive/CyberBees.vue'
import HiveFloor from './hive/HiveFloor.vue'
</script>

<template>
  <TresGroup>
    <primitive :object="new THREE.FogExp2(0x0a0a1a, 0.015)" attach="fog" />

    <HiveSphere />
    <CyberBees />
    <HiveFloor />

    <TresAmbientLight :color="'#1a1a3e'" :intensity="0.15" />
    <TresPointLight :position="[0, 8, 0]" :intensity="0.6" :color="'#00BFFF'" />
    <TresPointLight :position="[6, 3, 4]" :intensity="0.4" :color="'#d4af37'" />
    <TresPointLight :position="[-5, 2, -3]" :intensity="0.3" :color="'#FF6B9D'" />
  </TresGroup>
</template>
