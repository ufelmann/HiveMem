<script setup lang="ts">
import { computed, onMounted, shallowRef } from 'vue'
import { useNavigationStore } from '../../stores/navigation'
import { buildGoldbergCells, assignWings } from '../../composables/goldbergMath'
import type { GoldbergCell } from '../../composables/goldbergMath'
import { paletteForWing, type WingPalette } from '../../composables/wingPalette'
import HiveCell from './HiveCell.vue'

const store = useNavigationStore()
const R = 3

const cells = shallowRef<GoldbergCell[]>([])
const wingAssignment = shallowRef<Map<number, string | null>>(new Map())
const palettes = shallowRef<Map<string, WingPalette>>(new Map())

function rebuild() {
  cells.value = buildGoldbergCells(R, 1)
  wingAssignment.value = assignWings(
    cells.value,
    store.palace.wings.map((w) => ({ name: w.name, drawerCount: w.drawerCount })),
  )
  const pmap = new Map<string, WingPalette>()
  store.palace.wings.forEach((w, i) => pmap.set(w.name, paletteForWing(i, w.color)))
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
