<script setup lang="ts">
import * as THREE from 'three'
import { shallowRef, watchEffect, onBeforeUnmount } from 'vue'

const props = defineProps<{
  segments: { from: [number, number, number]; to: [number, number, number]; relation: string }[]
}>()

const relationColor: Record<string, string> = {
  related_to: '#00BFFF',
  builds_on: '#00FF88',
  contradicts: '#FF8C00',
  refines: '#c8a84e',
}

const lineObjects = shallowRef<{ key: number; object: THREE.Line }[]>([])

function disposeCurrent() {
  for (const { object } of lineObjects.value) {
    ;(object.geometry as THREE.BufferGeometry).dispose()
    ;(object.material as THREE.LineBasicMaterial).dispose()
  }
  lineObjects.value = []
}

watchEffect(() => {
  disposeCurrent()
  lineObjects.value = props.segments.map((s, idx) => {
    const geom = new THREE.BufferGeometry().setFromPoints([
      new THREE.Vector3(...s.from),
      new THREE.Vector3(...s.to),
    ])
    const mat = new THREE.LineBasicMaterial({
      color: relationColor[s.relation] ?? '#00BFFF',
      transparent: true,
      opacity: 0.75,
    })
    return { key: idx, object: new THREE.Line(geom, mat) }
  })
})

onBeforeUnmount(disposeCurrent)
</script>

<template>
  <TresGroup>
    <primitive v-for="line in lineObjects" :key="line.key" :object="line.object" />
  </TresGroup>
</template>
