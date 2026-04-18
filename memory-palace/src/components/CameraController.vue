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
      pos.set(0, 10, 14); look.set(0, 1.5, 0); break
    case 'wing':
      pos.set(0, 2.2, 0); look.set(1, 2.2, 0); break
    case 'hall':
      pos.set(-5, 1.8, 0); look.set(1, 1.8, 0); break
    case 'room':
      pos.set(0, 1.8, 0); look.set(Math.cos(Math.PI / 3), 1.8, Math.sin(Math.PI / 3)); break
    case 'drawer':
      if (store.focusedSheet !== null) {
        pos.set(0, 1.6, 3.2); look.set(0, 1.6, 0)
      } else {
        pos.set(0, 1.6, 6.0); look.set(0, 1.6, 0)
      }
      break
  }
  return { pos, look }
}

function animateTo(pos: THREE.Vector3, look: THREE.Vector3) {
  const cam = camera.activeCamera.value
  const ctrl: any = controls.value
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
  () => [store.level, store.currentWing, store.currentHall, store.currentRoom, store.selectedDrawerId, store.focusedSheet],
  apply,
  { immediate: false },
)

apply()

onBeforeUnmount(() => { if (currentTween) currentTween.kill() })
</script>

<template>
  <!-- No visual — purely reactive -->
</template>
