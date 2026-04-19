<script setup lang="ts">
import { computed } from 'vue'
import { useNavigationStore } from '../stores/navigation'

const store = useNavigationStore()
const items = computed(() => store.breadcrumbItems)

function onClick(idx: number) {
  if (store.isTransitioning) return
  if (idx === items.value.length - 1) return
  store.goToLevel(items.value[idx])
}
</script>

<template>
  <div class="breadcrumbs-wrap">
    <template v-for="(item, idx) in items" :key="idx">
      <span class="crumb" :class="{ active: idx === items.length - 1 }" @click="onClick(idx)">
        {{ item.title }}
      </span>
      <span v-if="idx < items.length - 1" class="sep">›</span>
    </template>
  </div>
</template>

<style scoped>
.breadcrumbs-wrap {
  display: flex;
  align-items: center;
  gap: 0.4rem;
  overflow-x: auto;
  white-space: nowrap;
  padding: 0.2rem 0.4rem;
  scrollbar-width: none;
}
.breadcrumbs-wrap::-webkit-scrollbar { display: none; }
.crumb {
  cursor: pointer;
  padding: 0.25rem 0.6rem;
  border-radius: 0.4rem;
  color: #9eb6d9;
  font-size: 0.9rem;
  transition: color 0.2s, background 0.2s;
}
.crumb:hover { color: #00BFFF; background: rgba(0, 191, 255, 0.08); }
.crumb.active { color: #00BFFF; font-weight: 500; }
.sep { color: #4a5266; }
</style>
