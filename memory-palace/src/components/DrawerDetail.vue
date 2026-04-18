<script setup lang="ts">
import { computed, ref } from 'vue'
import { useDisplay } from 'vuetify'
import { useNavigationStore } from '../stores/navigation'

const store = useNavigationStore()
const { mobile } = useDisplay()

const tab = ref<'l0' | 'l1' | 'l2' | 'l3'>('l1')

const isOpen = computed({
  get: () => store.level === 'drawer' && !!store.selectedDrawer,
  set: (v: boolean) => { if (!v) store.closeDrawer() },
})

const drawer = computed(() => store.selectedDrawer)

function tunnelTargetTitle(targetId: string): string {
  return store.drawersById[targetId]?.title ?? targetId
}

function goToTunnel(targetId: string) {
  store.goToTunnelTarget(targetId)
}
</script>

<template>
  <template v-if="drawer">
    <v-bottom-sheet v-if="mobile" v-model="isOpen" inset scrollable>
      <v-card color="surface" class="drawer-card">
        <v-card-title class="text-primary">{{ drawer.title }}</v-card-title>
        <v-card-subtitle>{{ drawer.wing }} / {{ drawer.hall }} / {{ drawer.room }}</v-card-subtitle>
        <v-tabs v-model="tab" density="compact" bg-color="surface-bright">
          <v-tab value="l0">L0 Content</v-tab>
          <v-tab value="l1">L1 Summary</v-tab>
          <v-tab value="l2">L2 Key Points</v-tab>
          <v-tab value="l3">L3 Insight</v-tab>
        </v-tabs>
        <v-card-text style="max-height: 50dvh; overflow:auto;">
          <div v-if="tab === 'l0'">{{ drawer.content }}</div>
          <div v-else-if="tab === 'l1'">{{ drawer.summary }}</div>
          <ul v-else-if="tab === 'l2'"><li v-for="p in drawer.keyPoints" :key="p">{{ p }}</li></ul>
          <div v-else>{{ drawer.insight || '(no insight)' }}</div>

          <v-divider class="my-3" />
          <div class="text-subtitle-2 mb-2">Tunnels</div>
          <v-list v-if="drawer.tunnels.length" density="compact" bg-color="transparent">
            <v-list-item v-for="t in drawer.tunnels" :key="t.targetId" @click="goToTunnel(t.targetId)">
              <template #prepend><v-icon :color="t.relation === 'contradicts' ? 'warning' : 'primary'">mdi-arrow-right-bold</v-icon></template>
              <v-list-item-title>{{ tunnelTargetTitle(t.targetId) }}</v-list-item-title>
              <v-list-item-subtitle>{{ t.relation }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
          <div v-else class="text-disabled">(no tunnels)</div>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn @click="isOpen = false">Close</v-btn></v-card-actions>
      </v-card>
    </v-bottom-sheet>

    <v-dialog v-else v-model="isOpen" width="720" scrollable>
      <v-card color="surface" class="drawer-card">
        <v-card-title class="text-primary">{{ drawer.title }}</v-card-title>
        <v-card-subtitle>{{ drawer.wing }} / {{ drawer.hall }} / {{ drawer.room }}</v-card-subtitle>
        <v-tabs v-model="tab" density="compact" bg-color="surface-bright">
          <v-tab value="l0">L0 Content</v-tab>
          <v-tab value="l1">L1 Summary</v-tab>
          <v-tab value="l2">L2 Key Points</v-tab>
          <v-tab value="l3">L3 Insight</v-tab>
        </v-tabs>
        <v-card-text style="max-height: 60dvh; overflow:auto;">
          <div v-if="tab === 'l0'">{{ drawer.content }}</div>
          <div v-else-if="tab === 'l1'">{{ drawer.summary }}</div>
          <ul v-else-if="tab === 'l2'"><li v-for="p in drawer.keyPoints" :key="p">{{ p }}</li></ul>
          <div v-else>{{ drawer.insight || '(no insight)' }}</div>

          <v-divider class="my-3" />
          <div class="text-subtitle-2 mb-2">Tunnels</div>
          <v-list v-if="drawer.tunnels.length" density="compact" bg-color="transparent">
            <v-list-item v-for="t in drawer.tunnels" :key="t.targetId" @click="goToTunnel(t.targetId)">
              <template #prepend><v-icon :color="t.relation === 'contradicts' ? 'warning' : 'primary'">mdi-arrow-right-bold</v-icon></template>
              <v-list-item-title>{{ tunnelTargetTitle(t.targetId) }}</v-list-item-title>
              <v-list-item-subtitle>{{ t.relation }}</v-list-item-subtitle>
            </v-list-item>
          </v-list>
          <div v-else class="text-disabled">(no tunnels)</div>
        </v-card-text>
        <v-card-actions><v-spacer /><v-btn @click="isOpen = false">Close</v-btn></v-card-actions>
      </v-card>
    </v-dialog>
  </template>
</template>

<style scoped>
.drawer-card {
  border: 1px solid rgba(0, 191, 255, 0.3);
  box-shadow: 0 0 24px rgba(0, 191, 255, 0.15);
}
</style>
