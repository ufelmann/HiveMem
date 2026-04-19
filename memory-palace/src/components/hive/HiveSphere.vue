<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { buildGoldbergCells, assignWings } from '../../composables/goldbergMath'
import type { GoldbergCell } from '../../composables/goldbergMath'
import { paletteForWing, type WingPalette } from '../../composables/wingPalette'
import type { Wing, Drawer } from '../../api/types'
import HiveCell from './HiveCell.vue'

const props = defineProps<{ wings: Wing[]; drawers: Drawer[] }>()

const R = 3

const cells = shallowRef<GoldbergCell[]>([])
const wingAssignment = shallowRef<Map<number, string | null>>(new Map())
const palettes = shallowRef<Map<string, WingPalette>>(new Map())

function rebuild() {
  cells.value = buildGoldbergCells(R, 1)
  // Use drawers array as authoritative count if wings have none, else use wing.drawer_count.
  const counts = new Map<string, number>()
  for (const d of props.drawers) counts.set(d.wing, (counts.get(d.wing) ?? 0) + 1)
  const wingInput = props.wings.map((w) => ({
    name: w.name,
    drawerCount: w.drawer_count || counts.get(w.name) || 0,
  }))
  wingAssignment.value = assignWings(cells.value, wingInput)
  const pmap = new Map<string, WingPalette>()
  props.wings.forEach((w, i) => pmap.set(w.name, paletteForWing(i)))
  palettes.value = pmap
}

onMounted(rebuild)

const cellList = computed(() => {
  return cells.value.map((c) => {
    const wn = wingAssignment.value.get(c.index) ?? null
    const pal = wn ? palettes.value.get(wn) ?? null : null
    const isPink = (c.index % 13) === 0 && wn !== null
    const isViolet = !isPink && (c.index % 17) === 0 && wn !== null
    return { cell: c, wingName: wn, palette: pal, isAccentPink: isPink, isAccentViolet: isViolet }
  })
})
</script>

<template>
  <TresGroup>
    <HiveCell
      v-for="item in cellList"
      :key="item.cell.index"
      :cell="item.cell"
      :wing-name="item.wingName"
      :palette="item.palette"
      :is-accent-pink="item.isAccentPink"
      :is-accent-violet="item.isAccentViolet"
    />
  </TresGroup>
</template>
