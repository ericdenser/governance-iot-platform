<script setup lang="ts">
import { ref, onMounted, watch } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { devicesApi } from '@/services/devices'
import { useAuthStore } from '@/stores/auth'
import { useDebouncedRef } from '@/composables/useDebouncedRef'
import type { DeviceSummaryDTO, DeviceStatus } from '@/types/models'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

const authStore = useAuthStore()
const devices = ref<DeviceSummaryDTO[]>([])
const loading = ref(true)
const search = ref('')
const debouncedSearch = useDebouncedRef(search, 300)
const statusFilter = ref<DeviceStatus | ''>('')
const page = ref(0)
const size = ref(50)
const totalPages = ref(0)
const totalElements = ref(0)

const STATUS_OPTIONS: DeviceStatus[] = ['PENDING', 'PROVISIONING', 'ACTIVE', 'COMMAND_PENDING', 'REVOKED', 'ERROR']

const statusVariant = (s: DeviceStatus): BadgeVariant => {
  const m: Record<string, BadgeVariant> = { ACTIVE: 'success', PENDING: 'warning', PROVISIONING: 'info', COMMAND_PENDING: 'info', REVOKED: 'danger' }
  return m[s] ?? 'muted'
}

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const load = async () => {
  loading.value = true
  try {
    const r = await devicesApi.list({
      page: page.value,
      size: size.value,
      search: debouncedSearch.value.trim() || undefined,
      status: statusFilter.value || undefined,
    })
    devices.value = r.data.content
    totalPages.value = r.data.page.totalPages
    totalElements.value = r.data.page.totalElements
  } finally {
    loading.value = false
  }
}

watch(debouncedSearch, () => {
  page.value = 0
  load()
})

watch(statusFilter, () => {
  page.value = 0
  load()
})

const goToPage = (n: number) => {
  if (n < 0 || n >= totalPages.value) return
  page.value = n
  load()
}

onMounted(load)
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
        <select v-model="statusFilter" class="status-select">
          <option value="">Todos os status</option>
          <option v-for="s in STATUS_OPTIONS" :key="s" :value="s">{{ s }}</option>
        </select>
        <span class="count text-muted text-sm">{{ totalElements }} dispositivos</span>
      </div>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Nome</th>
            <th>Device ID</th>
            <th>Status</th>
            <th>Firmware</th>
            <th>MAC</th>
            <th>Provisionado por</th>
            <th>Última vez visto</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="d in devices" :key="d.deviceId" class="tbl-row"
              @click="$router.push(`/devices/${d.deviceId}`)">
            <td class="font-medium">{{ d.name }}</td>
            <td class="mono text-sm">{{ d.deviceId }}</td>
            <td><AppBadge :variant="statusVariant(d.status)" dot>{{ d.status }}</AppBadge></td>
            <td class="text-sm">
              <template v-if="d.firmwareVersion">
                <span>{{ d.firmwareName }}</span>
                <span class="mono text-muted"> v{{ d.firmwareVersion }}</span>
              </template>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="mono text-sm">{{ d.macAddress ?? '—' }}</td>
            <td class="text-sm">
              <span v-if="d.issuedByUsername" class="issued">{{ d.issuedByUsername }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="text-sm text-muted">{{ fmt(d.lastSeen) }}</td>
          </tr>
          <tr v-if="!devices.length">
            <td colspan="7" class="empty">Nenhum dispositivo encontrado</td>
          </tr>
        </tbody>
      </table>

      <div v-if="!loading && totalPages > 1" class="pager">
        <AppButton size="sm" :disabled="page === 0" @click="goToPage(page - 1)">Anterior</AppButton>
        <span class="pager-info text-sm text-muted">
          Página {{ page + 1 }} de {{ totalPages }}
        </span>
        <AppButton size="sm" :disabled="page + 1 >= totalPages" @click="goToPage(page + 1)">Próxima</AppButton>
      </div>
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; gap: var(--space-3); margin-bottom: var(--space-4); flex-wrap: wrap; }
.search { flex: 1; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 7px 12px; font-size: var(--text-sm); color: var(--text); outline: none; }
.search:focus { border-color: var(--primary); }
.status-select { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 7px 12px; font-size: var(--text-sm); color: var(--text); outline: none; }
.status-select:focus { border-color: var(--primary); }
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
.issued { font-weight: 500; color: var(--text); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }

.pager { display: flex; align-items: center; justify-content: center; gap: var(--space-4); margin-top: var(--space-4); }
.pager-info { min-width: 140px; text-align: center; }
</style>
