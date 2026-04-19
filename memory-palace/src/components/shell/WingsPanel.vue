<script setup lang="ts">
import { onMounted } from 'vue'
import { useCanvasStore } from '../../stores/canvas'
import { useUiStore, type SizeMetric } from '../../stores/ui'

const canvas = useCanvasStore()
const ui = useUiStore()
const metrics: { v: SizeMetric; label: string }[] = [
  { v: 'drawer_count', label: 'Drawer count' },
  { v: 'content_volume', label: 'Content volume' },
  { v: 'importance', label: 'Importance-weighted' },
  { v: 'popularity', label: 'Popularity' }
]

onMounted(() => { if (!canvas.loaded) canvas.loadTopLevel() })

function colorFor(wing: string): string {
  let h = 0; for (let i = 0; i < wing.length; i++) h = (h * 31 + wing.charCodeAt(i)) % 360
  return `hsl(${h}, 70%, 55%)`
}
</script>

<template>
  <div>
    <v-list density="compact">
      <v-list-item v-for="w in canvas.wings" :key="w.name" :title="w.name" :subtitle="`${w.drawer_count} drawers`">
        <template #prepend>
          <span class="dot" :style="{ background: colorFor(w.name) }" />
        </template>
      </v-list-item>
    </v-list>
    <v-divider class="my-3" />
    <strong style="font-size:12px;letter-spacing:0.1em">SIZE METRIC</strong>
    <v-radio-group v-model="ui.sizeMetric" density="compact">
      <v-radio v-for="m in metrics" :key="m.v" :label="m.label" :value="m.v" />
    </v-radio-group>
  </div>
</template>

<style scoped>
.dot { display:inline-block; width:10px; height:10px; border-radius:50%; margin-right:8px; }
</style>
