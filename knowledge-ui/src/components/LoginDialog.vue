<script setup lang="ts">
import { ref } from 'vue'
import { useAuthStore } from '../stores/auth'
import { useUiStore } from '../stores/ui'

const auth = useAuthStore()
const ui = useUiStore()
const token = ref('')
const useMock = ref(localStorage.getItem('hivemem_mock') === 'true')
const error = ref('')

async function submit() {
  error.value = ''
  try {
    if (useMock.value) {
      localStorage.setItem('hivemem_mock', 'true')
      await auth.login('mock')
    } else {
      localStorage.setItem('hivemem_mock', 'false')
      await auth.login(token.value.trim())
    }
    ui.showLoginDialog = false
  } catch (e: any) { error.value = e?.message ?? 'Login failed' }
}
</script>

<template>
  <v-dialog v-model="ui.showLoginDialog" max-width="460" persistent>
    <v-card>
      <v-card-title>Connect to HiveMem</v-card-title>
      <v-card-text>
        <v-switch v-model="useMock" label="Use local mock data" color="primary" />
        <v-text-field
          v-if="!useMock"
          v-model="token"
          label="Bearer token"
          type="password"
          hint="Create with the hivemem-token CLI inside the container (role admin)"
          persistent-hint
        />
        <v-alert v-if="error" type="error" variant="tonal" class="mt-3">{{ error }}</v-alert>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn color="primary" @click="submit">Connect</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>
