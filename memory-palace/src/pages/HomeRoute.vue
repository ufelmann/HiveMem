<script setup lang="ts">
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
</script>
<template>
  <div class="home-root">
    <IconRail />
    <SlidePanel id="search" title="Search"><em>Search UI (Task 10)</em></SlidePanel>
    <SlidePanel id="wings" title="Wings"><em>Wings tree (Task 10)</em></SlidePanel>
    <SlidePanel id="settings" title="Settings"><em>Settings (Task 10)</em></SlidePanel>
    <main class="canvas-slot">
      <div style="padding:20px;color:#666">SphereCanvas goes here (Task 11)</div>
    </main>
  </div>
</template>

<style scoped>
.home-root { position:fixed; inset:0; background:#050510; color:#eee; }
.canvas-slot { position:absolute; inset:0 0 0 56px; }
</style>
