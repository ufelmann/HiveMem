<script setup lang="ts">
import { ref, watchEffect, onBeforeUnmount } from 'vue'

const props = defineProps<{ url: string }>()
const container = ref<HTMLDivElement>()
let viewer: any = null

watchEffect(async () => {
  if (!props.url || !container.value) return
  const pdfjs = await import('pdfjs-dist')
  const viewerMod: any = await import('pdfjs-dist/web/pdf_viewer.mjs')
  ;(pdfjs as any).GlobalWorkerOptions.workerSrc =
    new URL('pdfjs-dist/build/pdf.worker.min.mjs', import.meta.url).toString()
  const pdf = await pdfjs.getDocument(props.url).promise
  const eventBus = new viewerMod.EventBus()
  const linkService = new viewerMod.PDFLinkService({ eventBus })
  viewer = new viewerMod.PDFViewer({ container: container.value!, eventBus, linkService })
  linkService.setViewer(viewer)
  viewer.setDocument(pdf)
  linkService.setDocument(pdf, null)
})

onBeforeUnmount(() => { viewer?.cleanup?.() })
</script>

<template>
  <div ref="container" class="pdf-container"><div class="pdfViewer" /></div>
</template>

<style scoped>
.pdf-container { position: absolute; inset: 0; overflow: auto; }
</style>
