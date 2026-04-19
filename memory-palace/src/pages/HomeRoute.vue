<script setup lang="ts">
import IconRail from '../components/shell/IconRail.vue'
import SlidePanel from '../components/shell/SlidePanel.vue'
import SearchPanel from '../components/shell/SearchPanel.vue'
import WingsPanel from '../components/shell/WingsPanel.vue'
import SettingsPanel from '../components/shell/SettingsPanel.vue'
</script>
<template>
  <div class="home-root">
    <IconRail />
    <SlidePanel id="search" title="Search"><SearchPanel /></SlidePanel>
    <SlidePanel id="wings" title="Wings"><WingsPanel /></SlidePanel>
    <SlidePanel id="settings" title="Settings"><SettingsPanel /></SlidePanel>
    <main class="canvas-slot">
      <div style="padding:20px;color:#666">SphereCanvas goes here (Task 11)</div>
    </main>
  </div>
</template>

<style scoped>
.home-root { position:fixed; inset:0; background:#050510; color:#eee; }
.canvas-slot { position:absolute; inset:0 0 0 56px; }
</style>
