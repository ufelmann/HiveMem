<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTheme } from 'vuetify'
import { useAuthStore } from './stores/auth'
import { useUiStore } from './stores/ui'
import { useCanvasStore } from './stores/canvas'
import { usePrefsStore } from './stores/prefs'
import { loadAuthMode } from './api/authMode'
import { clearReauthGuard } from './api/reauth'
import AppShell from './components/shell/AppShell.vue'
import PwaReloadPrompt from './components/shell/PwaReloadPrompt.vue'

const { t } = useI18n()
const auth = useAuthStore()
const ui = useUiStore()
const canvas = useCanvasStore()
const prefs = usePrefsStore()
const vTheme = useTheme()
watch(() => prefs.theme, (v) => {
  vTheme.change(v === 'light' ? 'hivememLight' : 'hivememDark')
}, { immediate: true })

// A 401 is handled by httpClient itself (redirects to /login and navigates away).
// Any other failure (500, network drop, timeout — e.g. a backend container
// restart wiping the session) used to be swallowed silently here, leaving the
// user stuck on the "connecting…" splash forever with no way out (E5).
const authError = ref(false)
async function initAuth(forced = false) {
  authError.value = false
  // A deliberate retry must not be swallowed by the 5-minute re-auth guard the way
  // the automatic startup path deliberately is (see api/reauth.ts). Dropping the stamp
  // is enough: the next triggerReauth from httpClient then navigates for real.
  if (forced) clearReauthGuard()
  try {
    // Load the auth mode BEFORE auth.init() fires wake_up: a 401 during startup must
    // resolve against a known mode, not a guessed default (see api/authMode.ts).
    await loadAuthMode()
    await auth.init()
  } catch { authError.value = true }
}
onMounted(() => initAuth())

// canvas.loadTopLevel() can fail the same way any other API call can (network/
// backend restart); an unhandled rejection here used to just vanish, leaving
// the snackbar dismissed with no feedback that the reload didn't happen.
function onSnackbarReload() {
  try {
    const r = canvas.loadTopLevel()
    if (r && typeof (r as Promise<unknown>).catch === 'function') {
      (r as Promise<unknown>).catch(() => ui.pushToast('error', t('common.actionFailed')))
    }
  } catch {
    ui.pushToast('error', t('common.actionFailed'))
  }
  ui.toast = null
}
</script>

<template>
  <v-app>
    <v-main>
      <AppShell v-if="auth.isAuthenticated" />
      <div v-else-if="authError" class="splash error">
        <span>{{ t('common.connectError') }}</span>
        <v-btn variant="tonal" data-test="auth-retry" @click="initAuth(true)">{{ t('common.retry') }}</v-btn>
      </div>
      <div v-else class="splash">{{ t('common.connecting') }}</div>
    </v-main>
    <!-- Outside the isAuthenticated gate on purpose: a stale service worker serving '/'
         from its precache is what leaves the user stuck on the splash in the first place,
         so the prompt that activates the waiting worker must be reachable there too. -->
    <PwaReloadPrompt />
    <v-snackbar
      v-if="ui.toast"
      :color="ui.toast.kind"
      :model-value="!!ui.toast"
      timeout="8000"
      @update:model-value="(v: boolean) => { if (!v) ui.toast = null }"
    >
      {{ ui.toast.text }}
      <template #actions>
        <v-btn variant="text" @click="onSnackbarReload">{{ t('common.reload') }}</v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>

<style scoped>
.splash { display:flex; align-items:center; justify-content:center; height:100vh; color:var(--honey); }
.splash.error { flex-direction:column; gap:14px; color:var(--danger, #ff6b6b); }
</style>
