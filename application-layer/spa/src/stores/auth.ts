import { defineStore } from 'pinia'
import api from '@/services/api'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as { nome: string; username: string } | null,
    isAuthenticated: false,
    isAdmin: false,
    isInitialized: false,
  }),
  actions: {
    async checkAuth() {
      if (this.isInitialized) return

      try {
        const resp = await api.get('/me')
        this.isAuthenticated = resp.data.authenticated

        if (this.isAuthenticated) {
          this.user = { nome: resp.data.nome, username: resp.data.username }

          const roleResp = await api.get('/check-role?roles=ROLE_ADMIN')
          this.isAdmin = roleResp.data.hasRole
        }
      } catch {
        this.isAuthenticated = false
        this.isAdmin = false
      } finally {
        this.isInitialized = true
      }
    },

    clearAuth() {
      this.user = null
      this.isAuthenticated = false
      this.isAdmin = false
      this.isInitialized = true
    },
  },
})
