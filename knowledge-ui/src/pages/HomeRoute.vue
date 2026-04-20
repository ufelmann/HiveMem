<script setup lang="ts">
import { onBeforeUnmount, onMounted } from 'vue'
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
import SearchPanel from '../components/shell/SearchPanel.vue'
import RealmsPanel from '../components/shell/RealmsPanel.vue'
import SettingsPanel from '../components/shell/SettingsPanel.vue'
import SphereCanvas from '../components/canvas/SphereCanvas.vue'
import ScanPanel from '../components/ScanPanel.vue'
import Reader from '../components/Reader.vue'
import { useCanvasStore } from '../stores/canvas'
import { useApi } from '../api/useApi'
import { useUiStore } from '../stores/ui'
import { useKeybindings } from '../composables/keybindings'

const canvas = useCanvasStore()
const ui = useUiStore()
let unsub: (() => void) | null = null

onMounted(() => {
  if (!canvas.loaded) canvas.loadTopLevel()
  unsub = useApi().subscribe(e => {
    if (e.type === 'status' || e.type === 'cell_added' || e.type === 'tunnel_added') {
      ui.pushToast('info', 'New activity — click Reload to refresh')
    }
  })
})
onBeforeUnmount(() => unsub?.())

useKeybindings()
</script>
<template>
  <div class="home-root">
    <IconRail />
    <SlidePanel id="search" title="Search"><SearchPanel /></SlidePanel>
    <SlidePanel id="realms" title="Realms"><RealmsPanel /></SlidePanel>
    <SlidePanel id="settings" title="Settings"><SettingsPanel /></SlidePanel>
    <main class="canvas-slot">
      <SphereCanvas v-if="canvas.loaded" />
      <div v-else class="splash">Loading…</div>
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
