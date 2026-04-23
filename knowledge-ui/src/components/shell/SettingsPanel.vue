<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const mock = ref(localStorage.getItem('hivemem_mock') === 'true')

function toggleMock(v: boolean | null) {
  localStorage.setItem('hivemem_mock', String(v))
  window.location.reload()
}

function logoutAndReload() {
  auth.logout()
  window.location.reload()
}
</script>

<template>
  <v-list density="compact">
    <v-list-item :title="`Signed in as ${auth.identity ?? '…'}`" :subtitle="`Role: ${auth.role ?? 'none'}`" />
  </v-list>
  <v-switch v-model="mock" label="Mock mode" color="primary" @update:model-value="toggleMock" />
  <v-btn block color="error" variant="tonal" @click="logoutAndReload">Log out</v-btn>
</template>
