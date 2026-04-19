<script setup lang="ts">
import { ref, watch } from 'vue'
import { useApi } from '../../api/useApi'
import type { Drawer } from '../../api/types'
import { useDrawerStore } from '../../stores/drawer'

const q = ref('')
const results = ref<Drawer[]>([])
const loading = ref(false)
const drawer = useDrawerStore()

let timer: number | null = null
watch(q, v => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    loading.value = true
    try { results.value = await useApi().call<Drawer[]>('hivemem_search', { query: v, limit: 50 }) }
    finally { loading.value = false }
  }, 180) as unknown as number
})
</script>

<template>
  <v-text-field v-model="q" density="compact" variant="solo-filled" placeholder="Type to search…" autofocus />
  <v-list density="compact">
    <v-list-item v-for="d in results" :key="d.id" :title="d.title" :subtitle="d.wing + (d.hall ? ` · ${d.hall}` : '')"
                 @click="drawer.load(d.id)" />
  </v-list>
  <div v-if="loading" style="color:#666;padding:8px">Searching…</div>
</template>
