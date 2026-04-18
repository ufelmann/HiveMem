<script setup lang="ts">
import { computed } from 'vue'
import { useNavigationStore } from '../stores/navigation'
import DrawerCard, { type CardType } from './DrawerCard.vue'

const store = useNavigationStore()

interface CardSpec {
  idx: number
  type: CardType
  accent: string
}

const CARDS: CardSpec[] = [
  { idx: 0, type: 'cover',       accent: '#c8a84e' },
  { idx: 1, type: 'summary',     accent: '#00BFFF' },
  { idx: 2, type: 'keyPoints',   accent: '#00FF88' },
  { idx: 3, type: 'insight',     accent: '#c8a84e' },
  { idx: 4, type: 'content',     accent: '#9a6bff' },
  { idx: 5, type: 'connections', accent: '#FF8C00' },
]

function resolveTitle(targetId: string): string {
  return store.drawersById[targetId]?.title ?? targetId
}

function cardPose(cardIdx: number): { position: [number, number, number]; rotationX: number; visible: boolean; isTop: boolean } {
  const active = store.currentCardIndex
  const rel = cardIdx - active
  if (rel < 0) {
    return { position: [0, 0, 0], rotationX: 0, visible: false, isTop: false }
  }
  return {
    position: [0, 1.8 - rel * 0.02, -rel * 0.03],
    rotationX: 0,
    visible: rel < 3,
    isTop: rel === 0,
  }
}

function onTopClick() {
  if (store.currentCardIndex < 5) store.nextCard()
}

function onDirectClick(cardIdx: number) {
  store.setCurrentCard(cardIdx)
}

function onTunnelClick(targetId: string) {
  store.goToTunnelTarget(targetId)
}

function onClose() {
  store.closeDrawer()
}

const drawer = computed(() => store.selectedDrawer)
</script>

<template>
  <TresGroup v-if="drawer">
    <template v-for="c in CARDS" :key="c.idx">
      <DrawerCard
        v-bind="cardPose(c.idx)"
        :drawer="drawer"
        :type="c.type"
        :accent="c.accent"
        :target-resolver="resolveTitle"
        @top-click="onTopClick"
        @direct-click="onDirectClick(c.idx)"
        @tunnel-click="(id: string) => onTunnelClick(id)"
        @close="onClose"
      />
    </template>
  </TresGroup>
</template>
