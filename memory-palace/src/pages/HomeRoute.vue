<script setup lang="ts">
import { onMounted } from 'vue'
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
import SearchPanel from '../components/shell/SearchPanel.vue'
import WingsPanel from '../components/shell/WingsPanel.vue'
import SettingsPanel from '../components/shell/SettingsPanel.vue'
import SphereCanvas from '../components/canvas/SphereCanvas.vue'
import ScanPanel from '../components/ScanPanel.vue'
import Reader from '../components/Reader.vue'
import { useCanvasStore } from '../stores/canvas'

const canvas = useCanvasStore()
onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })
</script>
<template>
  <div class="home-root">
    <IconRail />
    <SlidePanel id="search" title="Search"><SearchPanel /></SlidePanel>
    <SlidePanel id="wings" title="Wings"><WingsPanel /></SlidePanel>
    <SlidePanel id="settings" title="Settings"><SettingsPanel /></SlidePanel>
    <main class="canvas-slot">
      <SphereCanvas v-if="canvas.loaded" />
      <div v-else class="splash">Loading palace…</div>
    </main>
    <ScanPanel />
    <Reader />
  </div>
</template>

<style scoped>
.home-root { position:fixed; inset:0; background:#050510; color:#eee; }
.canvas-slot { position:absolute; inset:0 0 0 56px; }
.splash { display:grid; place-items:center; height:100%; color:#4dc4ff; }
</style>
