<script setup lang="ts">
import { onMounted } from 'vue'
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
import SearchPanel from '../components/shell/SearchPanel.vue'
import RealmsPanel from '../components/shell/RealmsPanel.vue'
import SettingsPanel from '../components/shell/SettingsPanel.vue'
import ScanPanel from '../components/ScanPanel.vue'
import Reader from '../components/Reader.vue'
import { useCanvasStore } from '../stores/canvas'
import { useKeybindings } from '../composables/keybindings'

const canvas = useCanvasStore()

onMounted(() => {
  if (!canvas.loaded) canvas.loadTopLevel()
})

useKeybindings()
</script>

<template>
  <div class="graph-root">
    <IconRail />
    <SlidePanel id="search" title="Search"><SearchPanel /></SlidePanel>
    <SlidePanel id="realms" title="Realms"><RealmsPanel /></SlidePanel>
    <SlidePanel id="settings" title="Settings"><SettingsPanel /></SlidePanel>
    <main class="graph-slot">Graph view placeholder</main>
    <ScanPanel />
    <Reader />
  </div>
</template>

<style scoped>
.graph-root { position:fixed; inset:0; background:#050510; color:#eee; }
.graph-slot { position:absolute; inset:0 0 0 56px; display:grid; place-items:center; color:#4dc4ff; }
</style>
