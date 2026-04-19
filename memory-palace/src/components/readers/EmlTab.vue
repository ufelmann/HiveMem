<script setup lang="ts">
import { ref, watchEffect } from 'vue'
import PostalMime from 'postal-mime'

const props = defineProps<{ url: string }>()
const parsed = ref<any>(null)
const err = ref('')

watchEffect(async () => {
  if (!props.url) return
  try {
    const raw = await fetch(props.url).then(r => r.text())
    parsed.value = await new PostalMime().parse(raw)
  } catch (e: any) {
    err.value = e?.message ?? 'failed to parse'
  }
})
</script>

<template>
  <article class="eml">
    <v-alert v-if="err" type="error" variant="tonal">{{ err }}</v-alert>
    <template v-else-if="parsed">
      <header>
        <div><strong>From:</strong> {{ parsed.from?.address }}</div>
        <div><strong>To:</strong> {{ parsed.to?.map((t: any) => t.address).join(', ') }}</div>
        <div><strong>Subject:</strong> {{ parsed.subject }}</div>
        <div><strong>Date:</strong> {{ parsed.date }}</div>
      </header>
      <div class="body" v-html="parsed.html || parsed.text || ''"></div>
    </template>
  </article>
</template>

<style scoped>
.eml { max-width: 780px; margin: 0 auto; font-family: system-ui, sans-serif; padding: 20px 0; color: #e8e8ea; }
header { border-bottom: 1px solid #2a2a3a; padding-bottom: 12px; margin-bottom: 18px; font-size: 13px; color: #aaa; }
header strong { color: #eee; margin-right: 4px; }
.body { line-height: 1.55; font-size: 15px; }
.body :deep(blockquote) { border-left: 2px solid #666; padding-left: 10px; color: #999; }
</style>
