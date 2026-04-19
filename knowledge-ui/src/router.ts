import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', name: 'home', component: () => import('./pages/HomeRoute.vue') },
  { path: '/cinema', name: 'cinema', component: () => import('./pages/CinemaRoute.vue') }
]

export const router = createRouter({ history: createWebHistory(), routes })
