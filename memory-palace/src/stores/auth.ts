import { defineStore } from 'pinia'
import type { Role } from '../api/types'
import { useApi, resetApi } from '../api/useApi'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: (typeof localStorage !== 'undefined' ? localStorage.getItem('hivemem_token') : null) as string | null,
    role: null as Role | null,
    identity: null as string | null
  }),
  getters: { isAuthenticated: (s) => !!s.token && !!s.role },
  actions: {
    async login(token: string) {
      localStorage.setItem('hivemem_token', token)
      this.token = token
      resetApi()
      const api = useApi()
      const w = await api.call<{ role: Role; identity: string }>('hivemem_wake_up')
      this.role = w.role; this.identity = w.identity
    },
    logout() {
      localStorage.removeItem('hivemem_token')
      this.token = null; this.role = null; this.identity = null
      resetApi()
    }
  }
})
