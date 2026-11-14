import { defineStore } from 'pinia'
import api from '@/services/api'
import type { MeResponse, CheckRoleResponse, DeviceGroupResponseDTO } from '@/types/models'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null as { nome: string; username: string } | null,
    keycloakUserId: null as string | null,
    isAuthenticated: false,
    isAdmin: false,
    isInitialized: false,
    // Groups where the user is MEMBER/OWNER (can provision); null = not loaded yet
    managedGroupIds: null as string[] | null,
  }),
  getters: {
    canProvision: (state): boolean => state.isAdmin || (state.managedGroupIds?.length ?? 0) > 0,
  },
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

    async loadManagedGroups(force = false) {
      if (!this.isAuthenticated || (this.managedGroupIds && !force)) return
      try {
        const r = await api.get<DeviceGroupResponseDTO[]>('/groups')
        const groups = Array.isArray(r.data) ? r.data : []
        this.managedGroupIds = groups
          .filter(g => g.myRole === 'MEMBER' || g.myRole === 'OWNER')
          .map(g => g.groupId)
      } catch {
        this.managedGroupIds = []
      }
    },

    clearAuth() {
      this.user = null
      this.keycloakUserId = null
      this.isAuthenticated = false
      this.isAdmin = false
      this.managedGroupIds = null
      this.isInitialized = true
    },
  },
})
