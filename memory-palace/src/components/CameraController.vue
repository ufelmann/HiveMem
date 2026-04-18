<script setup lang="ts">
import { watch, onBeforeUnmount } from 'vue'
import { useTresContext } from '@tresjs/core'
import gsap from 'gsap'
import * as THREE from 'three'
import { useNavigationStore } from '../stores/navigation'

const store = useNavigationStore()
const { camera, controls } = useTresContext()

let currentTween: gsap.core.Timeline | null = null

function poseForLevel(): { pos: THREE.Vector3; look: THREE.Vector3 } {
  const pos = new THREE.Vector3()
  const look = new THREE.Vector3()
  switch (store.level) {
    case 'building':
      pos.set(0, 2, 8); look.set(0, 0, 0); break
    case 'wing':
      pos.set(-3, 2.4, 0); look.set(4, 2.4, 0); break
    case 'hall': {
      // Aim at first portal (theta = PI/6) on sphere surface at y=2
      const t0 = Math.PI / 6
      const rEq = Math.sqrt(6 * 6 - 2 * 2)
      pos.set(0, 2.0, 0); look.set(rEq * Math.cos(t0), 2.0, rEq * Math.sin(t0))
      break
    }
    case 'room':
      pos.set(-2, 1.8, -2); look.set(2 * Math.cos(Math.PI / 3), 1.8, 2 * Math.sin(Math.PI / 3)); break
    case 'drawer': {
      const cardIdx = store.currentCardIndex
      const z = 2.2 - cardIdx * 0.05
      pos.set(0, 1.8, z); look.set(0, 1.8, 0)
      break
    }
  }
  return { pos, look }
}

function animateTo(pos: THREE.Vector3, look: THREE.Vector3) {
  const cam = camera.activeCamera.value
  // TresJS v5 useTresContext().controls is null; grab from window exposed by PalaceScene
  const ctrl: any = (window as any).__palaceOrbit ?? controls.value
  if (!cam) return
  if (currentTween) currentTween.kill()
  store.setTransitioning(true)
  if (ctrl) ctrl.enabled = false

  const startLook = new THREE.Vector3()
  cam.getWorldDirection(startLook)
  startLook.multiplyScalar(10).add(cam.position)

  const lookProxy = { x: startLook.x, y: startLook.y, z: startLook.z }

  currentTween = gsap.timeline({
    defaults: { duration: 0.9, ease: 'power2.inOut' },
    onUpdate: () => {
      cam.lookAt(lookProxy.x, lookProxy.y, lookProxy.z)
      if (ctrl && ctrl.target) ctrl.target.set(lookProxy.x, lookProxy.y, lookProxy.z)
    },
    onComplete: () => {
      store.setTransitioning(false)
      if (ctrl) {
        ctrl.enabled = true
        if (ctrl.update) ctrl.update()
      }
    },
  })
  currentTween.to(cam.position, { x: pos.x, y: pos.y, z: pos.z }, 0)
  currentTween.to(lookProxy, { x: look.x, y: look.y, z: look.z }, 0)
}

function apply() {
  const { pos, look } = poseForLevel()
  animateTo(pos, look)
}

watch(
  () => [
    store.level,
    store.currentWing,
    store.currentHall,
    store.currentRoom,
    store.selectedDrawerId,
    store.currentCardIndex,
  ],
  apply,
  { immediate: false },
)

apply()

onBeforeUnmount(() => { if (currentTween) currentTween.kill() })
</script>

<template>
  <!-- No visual — purely reactive -->
</template>
