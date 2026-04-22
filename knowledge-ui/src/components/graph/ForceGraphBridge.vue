<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { createElement } from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { useCanvasStore } from '../../stores/canvas'
import { useCellStore } from '../../stores/cell'
import { mapCanvasToForceGraph } from '../../graph/mapCanvasToForceGraph'
import { ForceGraphRoot } from './ForceGraphRoot'

const el = ref<HTMLElement | null>(null)
const canvas = useCanvasStore()
const cell = useCellStore()
const graph = computed(() => mapCanvasToForceGraph({ cells: canvas.cells, tunnels: canvas.tunnels }))

let root: Root | null = null

function renderReact() {
  if (!root) return

  root.render(createElement(ForceGraphRoot, {
    nodes: graph.value.nodes,
    links: graph.value.links,
    onNodeHover: id => canvas.setHover(id),
    onNodeClick: id => {
      canvas.setFocus(id)
      void cell.load(id)
    }
  }))
}

onMounted(() => {
  if (!el.value) return
  root = createRoot(el.value)
  renderReact()
})

watch(graph, renderReact)

onBeforeUnmount(() => {
  root?.unmount()
  root = null
})
</script>

<template>
  <div ref="el" data-testid="force-graph-bridge" class="graph-bridge" />
</template>

<style scoped>
.graph-bridge {
  width: 100%;
  height: 100%;
}
</style>
