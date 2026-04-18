<script setup lang="ts">
import { onMounted } from 'vue'
import { useNavigationStore } from './stores/navigation'
import PalaceScene from './components/PalaceScene.vue'
import HudOverlay from './components/HudOverlay.vue'
import DrawerDetail from './components/DrawerDetail.vue'

const store = useNavigationStore()

onMounted(async () => {
  await store.loadPalace()
})
</script>

<template>
  <v-app theme="palace">
    <div class="scene-root">
      <PalaceScene v-if="store.loaded" />
      <div v-else class="splash">Loading palace…</div>
    </div>
    <HudOverlay v-if="store.loaded" />
    <DrawerDetail v-if="store.loaded" />
  </v-app>
</template>

<style scoped>
.scene-root { width: 100%; height: 100dvh; }
.splash {
  display: flex; align-items: center; justify-content: center;
  width: 100%; height: 100%;
  color: #00BFFF; font-size: 1.2rem; letter-spacing: 0.1em;
}
</style>
