<script setup lang="ts">
import { ref, watch } from 'vue'
import { useApi } from '../../api/useApi'
import type { Cell } from '../../api/types'
import { useCellStore } from '../../stores/cell'

const q = ref('')
const results = ref<Cell[]>([])
const loading = ref(false)
const cellStore = useCellStore()

let timer: number | null = null
watch(q, v => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    loading.value = true
    try { results.value = await useApi().call<Cell[]>('hivemem_search', { query: v, limit: 50 }) }
    finally { loading.value = false }
  }, 180) as unknown as number
})
</script>

<template>
  <v-text-field v-model="q" density="compact" variant="solo-filled" placeholder="Type to search…" autofocus />
  <v-list density="compact">
    <v-list-item v-for="c in results" :key="c.id" :title="c.title" :subtitle="c.realm + (c.signal ? ` · ${c.signal}` : '')"
                 @click="cellStore.load(c.id)" />
  </v-list>
  <div v-if="loading" style="color:#666;padding:8px">Searching…</div>
</template>
