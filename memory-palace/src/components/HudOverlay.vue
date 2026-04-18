<script setup lang="ts">
import { onMounted, onBeforeUnmount, ref } from 'vue'
import { useNavigationStore } from '../stores/navigation'
import BreadcrumbNav from './BreadcrumbNav.vue'

const store = useNavigationStore()
const isFullscreen = ref(false)

function onKey(e: KeyboardEvent) {
  if (e.key === 'Escape') { store.goBack() }
}

async function toggleFullscreen() {
  if (!document.fullscreenElement) {
    await document.documentElement.requestFullscreen()
    isFullscreen.value = true
  } else {
    await document.exitFullscreen()
    isFullscreen.value = false
  }
}

function onFullscreenChange() {
  isFullscreen.value = !!document.fullscreenElement
}

onMounted(() => {
  window.addEventListener('keydown', onKey)
  document.addEventListener('fullscreenchange', onFullscreenChange)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKey)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
})
</script>

<template>
  <div class="hud">
    <BreadcrumbNav class="hud-breadcrumbs" />
    <v-btn
      icon="mdi-fullscreen"
      size="small"
      variant="text"
      color="primary"
      class="hud-fs"
      @click="toggleFullscreen"
    />
  </div>
</template>

<style scoped>
.hud {
  position: fixed;
  top: 0; left: 0; right: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.6rem 0.8rem;
  background: linear-gradient(180deg, rgba(10,10,26,0.85), rgba(10,10,26,0));
  pointer-events: none;
  z-index: 10;
}
.hud-breadcrumbs, .hud-fs { pointer-events: auto; }
</style>
