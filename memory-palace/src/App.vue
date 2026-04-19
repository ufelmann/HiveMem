<script setup lang="ts">
import { onMounted } from 'vue'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { useCanvasStore } from './stores/canvas'
import LoginDialog from './components/LoginDialog.vue'

const auth = useAuthStore()
const ui = useUiStore()
const canvas = useCanvasStore()

onMounted(async () => {
  if (!auth.token) {
    ui.showLoginDialog = true
  } else {
    try { await auth.login(auth.token) } catch { ui.showLoginDialog = true }
  }
})
</script>

<template>
  <v-app>
    <v-main>
      <router-view v-if="auth.isAuthenticated" />
      <div v-else class="splash">Connecting…</div>
    </v-main>
    <LoginDialog />
    <v-snackbar v-if="ui.toast" :color="ui.toast.kind" :model-value="!!ui.toast" timeout="8000">
      {{ ui.toast.text }}
      <template #actions>
        <v-btn variant="text" @click="canvas.loadTopLevel(); ui.toast = null">Reload</v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>

<style scoped>
.splash { display:flex; align-items:center; justify-content:center; height:100vh; color:#4dc4ff; }
</style>
