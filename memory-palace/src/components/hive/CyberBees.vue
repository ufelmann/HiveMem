<script setup lang="ts">
import { onBeforeUnmount, shallowRef } from 'vue'
import * as THREE from 'three'
import { useLoop } from '@tresjs/core'
import { getGoldParticleTexture } from '../../composables/useTextures'

const isMobile = typeof window !== 'undefined' && window.matchMedia('(max-width: 768px)').matches
const BEE_COUNT = isMobile ? 10 : 20
const R_BASE = 3

interface BeeState { phase: number; radius: number; yPhase: number; ySpan: number; speed: number; y0: number }
const bees: BeeState[] = []
for (let i = 0; i < BEE_COUNT; i++) {
  bees.push({
    phase: Math.random() * Math.PI * 2,
    radius: R_BASE + 0.3 + Math.random() * 0.5,
    yPhase: Math.random() * Math.PI * 2,
    ySpan: 0.15 + Math.random() * 0.2,
    speed: 0.2 + Math.random() * 0.4,
    y0: (Math.random() - 0.5) * 1.6,
  })
}

const positions = new Float32Array(BEE_COUNT * 3)
const geometry = new THREE.BufferGeometry()
geometry.setAttribute('position', new THREE.BufferAttribute(positions, 3))

const material = new THREE.PointsMaterial({
  map: getGoldParticleTexture(),
  color: '#d4af37',
  size: 0.08,
  transparent: true,
  opacity: 0.9,
  depthWrite: false,
  blending: THREE.AdditiveBlending,
  sizeAttenuation: true,
})

const points = new THREE.Points(geometry, material)

const { onBeforeRender } = useLoop()
const stopLoop = onBeforeRender(({ delta }) => {
  const dt = Math.min(0.033, delta)
  for (let i = 0; i < BEE_COUNT; i++) {
    const b = bees[i]
    b.phase += b.speed * dt
    b.yPhase += dt * 0.9
    positions[i * 3]     = b.radius * Math.cos(b.phase)
    positions[i * 3 + 1] = b.y0 + b.ySpan * Math.sin(b.yPhase)
    positions[i * 3 + 2] = b.radius * Math.sin(b.phase)
  }
  ;(geometry.attributes.position as THREE.BufferAttribute).needsUpdate = true
})

onBeforeUnmount(() => {
  stopLoop.off()
  geometry.dispose()
  material.dispose()
})

const _object = shallowRef(points)
</script>

<template>
  <primitive :object="_object" />
</template>
