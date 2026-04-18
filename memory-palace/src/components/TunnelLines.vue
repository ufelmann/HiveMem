<script setup lang="ts">
import * as THREE from 'three'
import { computed } from 'vue'

const props = defineProps<{
  segments: { from: [number, number, number]; to: [number, number, number]; relation: string }[]
}>()

const relationColor: Record<string, string> = {
  related_to: '#00BFFF',
  builds_on: '#00FF88',
  contradicts: '#FF8C00',
  refines: '#c8a84e',
}

const lineObjects = computed(() =>
  props.segments.map((s, idx) => {
    const geom = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(...s.from),
      new THREE.Vector3(...s.to),
    ])
    const mat = new THREE.LineBasicMaterial({
      color: relationColor[s.relation] ?? '#00BFFF',
      transparent: true,
      opacity: 0.75,
    })
    const line = new THREE.Line(geom, mat)
    return { key: idx, object: line }
  })
)
</script>

<template>
  <TresGroup>
    <primitive v-for="line in lineObjects" :key="line.key" :object="line.object" />
  </TresGroup>
</template>
