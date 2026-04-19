import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { vuetify } from './plugins/vuetify'
import { router } from './router'
import App from './App.vue'
import './style.css'

createApp(App).use(createPinia()).use(vuetify).use(router).mount('#app')
