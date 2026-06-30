<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { devicesApi } from '@/services/devices'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const devices = ref<any[]>([])
const loading = ref(true)
const search = ref('')

const filtered = () => {
  const q = search.value.toLowerCase()
  if (!q) return devices.value
  return devices.value.filter(d =>
    d.name?.toLowerCase().includes(q) ||
    d.deviceId?.toLowerCase().includes(q) ||
    d.macAddress?.toLowerCase().includes(q)
  )
}

const statusVariant = (s: string): any => {
  const m: Record<string, string> = { ACTIVE: 'success', PENDING: 'warning', PROVISIONING: 'info', COMMAND_PENDING: 'info', REVOKED: 'danger' }
  return m[s] ?? 'muted'
}

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

onMounted(async () => {
  try { const r = await devicesApi.list(); devices.value = r.data }
  finally { loading.value = false }
})
</script>

<template>
  <AppLayout>
    <AppCard title="Dispositivos">
      <template #actions>
        <AppButton v-if="authStore.isAdmin" variant="primary" size="lg"
                   @click="$router.push('/firmware')">
          Provisionar 
        </AppButton>
      </template>

      <div class="toolbar">
        <input v-model="search" class="search" placeholder="Buscar por nome, ID ou MAC..." />
        <span class="count text-muted text-sm">{{ filtered().length }} dispositivos</span>
      </div>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Nome</th>
            <th>Device ID</th>
            <th>Status</th>
            <th>MAC</th>
            <th>Provisionado por</th>
            <th>Última vez visto</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in filtered()" :key="d.deviceId" class="tbl-row"
              @click="$router.push(`/devices/${d.deviceId}`)">
            <td class="font-medium">{{ d.name }}</td>
            <td class="mono text-sm">{{ d.deviceId }}</td>
            <td><AppBadge :variant="statusVariant(d.status)" dot>{{ d.status }}</AppBadge></td>
            <td class="mono text-sm">{{ d.macAddress ?? '—' }}</td>
            <td class="text-sm">
              <span v-if="d.issuedByUsername" class="issued">{{ d.issuedByUsername }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="text-sm text-muted">{{ fmt(d.lastSeen) }}</td>
          </tr>
          <tr v-if="!filtered().length">
            <td colspan="6" class="empty">Nenhum dispositivo encontrado</td>
          </tr>
        </tbody>
      </table>
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: var(--space-4); margin-bottom: var(--space-4); }
.search { flex: 1; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 7px 12px; font-size: var(--text-sm); color: var(--text); outline: none; }
.search:focus { border-color: var(--primary); }
.count { flex-shrink: 0; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); font-size: var(--text-sm); }
.tbl-row { cursor: pointer; }
.tbl-row:hover td { background: var(--panel); }

.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; color: var(--text); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.issued { font-weight: 500; color: var(--info); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
</style>
