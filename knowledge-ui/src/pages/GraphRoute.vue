<script setup lang="ts">
import { onMounted } from 'vue'
import IconRail from '../components/shell/IconRail.vue'
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
    <main class="graph-slot">Graph view placeholder</main>
    <ScanPanel />
    <Reader />
  </div>
</template>

<style scoped>
.graph-root { position:fixed; inset:0; background:#050510; color:#eee; }
.graph-slot { position:absolute; inset:0 0 0 56px; display:grid; place-items:center; color:#4dc4ff; }
</style>
