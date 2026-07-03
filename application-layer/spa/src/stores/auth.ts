import { defineStore } from 'pinia'
import api from '@/services/api'
import type { MeResponse, CheckRoleResponse } from '@/types/models'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as { nome: string; username: string } | null,
    keycloakUserId: null as string | null,
    isAuthenticated: false,
    isAdmin: false,
    isInitialized: false,
  }),
  actions: {
    async checkAuth() {
      if (this.isInitialized) return

      try {
        const resp = await api.get<MeResponse>('/me')
        this.isAuthenticated = resp.data.authenticated

        if (this.isAuthenticated) {
          this.user = { nome: resp.data.nome ?? '', username: resp.data.username ?? '' }
          this.keycloakUserId = resp.data.keycloakUserId ?? null

          const roleResp = await api.get<CheckRoleResponse>('/check-role?roles=ROLE_ADMIN')
          this.isAdmin = roleResp.data.hasRole
        }
      } catch {
        this.isAuthenticated = false
        this.isAdmin = false
        this.keycloakUserId = null
      } finally {
        this.isInitialized = true
      }
    },

    clearAuth() {
      this.user = null
      this.keycloakUserId = null
      this.isAuthenticated = false
      this.isAdmin = false
      this.isInitialized = true
    },
  },
})
