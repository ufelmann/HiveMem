<script setup lang="ts">
import { useUiStore } from '../../stores/ui'
const ui = useUiStore()
defineProps<{ id: Exclude<typeof ui.activePanel, null>; title: string }>()
</script>

<template>
  <transition name="slide">
    <aside v-if="ui.activePanel === id" class="panel">
      <header>
        <strong>{{ title }}</strong>
        <v-btn icon="mdi-close" size="small" variant="text" @click="ui.activePanel = null" />
      </header>
      <div class="body"><slot /></div>
    </aside>
  </transition>
</template>

<style scoped>
.panel { position:fixed; top:0; bottom:0; left:56px; width:320px; background:#0e0e1c; border-right:1px solid #1a1a24; z-index:9; display:flex; flex-direction:column; }
header { display:flex; align-items:center; justify-content:space-between; padding:10px 14px; border-bottom:1px solid #1a1a24; }
.body { flex:1; overflow-y:auto; padding:12px 14px; }
.slide-enter-from, .slide-leave-to { transform:translateX(-20px); opacity:0; }
.slide-enter-active, .slide-leave-active { transition:transform 160ms ease, opacity 160ms ease; }
</style>
