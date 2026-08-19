import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { vuetify } from './plugins/vuetify'
import { router } from './router'
import { i18n } from './i18n'
import { usePrefsStore } from './stores/prefs'
import { registerSW } from 'virtual:pwa-register'
import App from './App.vue'
import './styles/tokens.css'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(vuetify)
app.use(router)
app.use(i18n)

const prefs = usePrefsStore()
prefs.init()

app.mount('#app')

// autoUpdate: the new worker takes over on its own, so there is no refresh callback.
registerSW()
