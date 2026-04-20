<script setup lang="ts">
import { computed } from 'vue'
import { useReaderStore } from '../stores/reader'
import { useCellStore } from '../stores/cell'
import MarkdownTab from './readers/MarkdownTab.vue'
import PdfTab from './readers/PdfTab.vue'
import EmlTab from './readers/EmlTab.vue'

const reader = useReaderStore()
const cellStore = useCellStore()

const attachments = computed(() => {
  // Populated later from cell references; empty in SP1 v1
  return [] as { id: string; title: string; url: string; kind: 'pdf' | 'eml' }[]
})

function kindOf(tab: string) { return attachments.value.find(a => a.id === tab)?.kind }
function urlOf(tab: string) { return attachments.value.find(a => a.id === tab)?.url ?? '' }
</script>

<template>
  <v-dialog v-model="reader.open" fullscreen transition="dialog-bottom-transition" persistent>
    <div class="reader-shell" v-if="cellStore.current">
      <header>
        <v-btn icon="mdi-arrow-left" variant="text" @click="reader.close()" />
        <v-tabs v-model="reader.activeTab" density="compact" color="primary">
          <v-tab value="markdown">Markdown</v-tab>
          <v-tab v-for="a in attachments" :key="a.id" :value="a.id">{{ a.title }}</v-tab>
        </v-tabs>
        <v-spacer />
        <v-btn icon="mdi-pencil" variant="text" disabled title="Editor — SP4" />
      </header>
      <main class="reader-body">
        <MarkdownTab v-if="reader.activeTab === 'markdown'" :content="cellStore.current.cell.content" />
        <PdfTab v-else-if="kindOf(reader.activeTab) === 'pdf'" :url="urlOf(reader.activeTab)" />
        <EmlTab v-else-if="kindOf(reader.activeTab) === 'eml'" :url="urlOf(reader.activeTab)" />
      </main>
    </div>
  </v-dialog>
</template>

<style scoped>
.reader-shell { position:fixed; inset:0; background:#0a0a14; display:flex; flex-direction:column; }
header { display:flex; align-items:center; padding:6px 10px; background:#12121e; border-bottom:1px solid #1a1a24; }
.reader-body { flex:1; overflow-y:auto; padding:0 20px; }
</style>
