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
const size = ref({ width: 0, height: 0 })

let root: Root | null = null
let resizeObserver: ResizeObserver | null = null

function updateSize(measurement?: { width: number; height: number }) {
  if (!el.value && !measurement) return

  const { width, height } = measurement ?? el.value!.getBoundingClientRect()
  size.value = {
    width: Math.round(width),
    height: Math.round(height)
  }
}

function renderReact() {
  if (!root) return

  root.render(createElement(ForceGraphRoot, {
    nodes: graph.value.nodes,
    links: graph.value.links,
    width: size.value.width,
    height: size.value.height,
    focusedId: canvas.focusedId,
    hoveredId: canvas.hoveredId,
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
  updateSize()
  resizeObserver = new ResizeObserver(entries => updateSize(entries[0]?.contentRect))
  resizeObserver.observe(el.value)
  renderReact()
})

watch([graph, size, () => canvas.focusedId, () => canvas.hoveredId], renderReact)

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  resizeObserver = null
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
