import { defineStore } from 'pinia'
import type { Role } from '../api/types'
import { useApi, resetApi } from '../api/useApi'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    role: null as Role | null,
    identity: null as string | null
  }),
  getters: { isAuthenticated: (s) => !!s.role },
  actions: {
    async init() {
      const api = useApi()
      const w = await api.call<{ role: Role; identity: string }>('wake_up')
      this.role = w.role
      this.identity = w.identity
    },
    async logout() {
      await fetch('/logout', { method: 'POST' })
      this.role = null
      this.identity = null
      resetApi()
      window.location.href = '/login'
    }
  }
})
