<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import StatCard from '@/components/StatCard.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import { devicesApi } from '@/services/devices'
import { eventsApi } from '@/services/events'
import { errorsApi } from '@/services/errors'

const devices = ref<any[]>([])
const recentEvents = ref<any[]>([])
const recentErrors = ref<any[]>([])
const totalEvents = ref(0)
const totalErrors = ref(0)
const loading = ref(true)

const totalDevices = computed(() => devices.value.length)
const activeDevices = computed(() => devices.value.filter(d => d.status === 'ACTIVE').length)
const pendingDevices = computed(() => devices.value.filter(d => ['PENDING', 'PROVISIONING'].includes(d.status)).length)

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

const statusVariant = (s: string): BadgeVariant => {
  const map: Record<string, BadgeVariant> = {
    ACTIVE: 'success', PENDING: 'warning', PROVISIONING: 'info',
    COMMAND_PENDING: 'info', REVOKED: 'danger', ERROR: 'danger',
  }
  return map[s] ?? 'muted'
}

const eventVariant = (t: string): BadgeVariant => {
  if (t?.includes('ERROR') || t?.includes('FAIL')) return 'danger'
  if (t?.includes('OTA') || t?.includes('UPDATE')) return 'info'
  return 'muted'
}

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

onMounted(async () => {
  try {
    const [devRes, evRes, errRes] = await Promise.all([
      devicesApi.list(),
      eventsApi.list(0),
      errorsApi.list(0),
    ])
    devices.value = devRes.data
    recentEvents.value = evRes.data.content?.slice(0, 5) ?? []
    totalEvents.value = evRes.data.page?.totalElements ?? 0
    recentErrors.value = errRes.data.content?.slice(0, 5) ?? []
    totalErrors.value = errRes.data.page?.totalElements ?? 0
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <AppLayout>
    <div v-if="loading" class="loading">Carregando...</div>
    <div v-else class="dashboard">

      <div class="stat-grid">
        <StatCard label="Total de Dispositivos" :value="totalDevices" />
        <StatCard label="Ativos" :value="activeDevices" variant="success" />
        <StatCard label="Pendentes / Provisionando" :value="pendingDevices" variant="warning" />
        <StatCard label="Erros Registrados" :value="totalErrors" variant="danger" />
      </div>

      <div class="panels">
        <AppCard title="Dispositivos Recentes" class="panel">
          <table class="tbl">
            <thead><tr><th>Nome</th><th>Status</th><th>Última vez visto</th></tr></thead>
            <tbody>
              <tr v-for="d in devices.slice(0, 8)" :key="d.deviceId" class="tbl-row"
                  @click="$router.push(`/devices/${d.deviceId}`)">
                <td class="mono">{{ d.name }}</td>
                <td><AppBadge :variant="statusVariant(d.status)" dot>{{ d.status }}</AppBadge></td>
                <td class="muted">{{ fmt(d.lastSeen) }}</td>
              </tr>
              <tr v-if="!devices.length"><td colspan="3" class="empty">Nenhum dispositivo</td></tr>
            </tbody>
          </table>
        </AppCard>

        <div class="side-panels">
          <AppCard title="Eventos Recentes" class="panel">
            <ul class="activity-list">
              <li v-for="e in recentEvents" :key="e.id" class="activity-item">
                <AppBadge :variant="eventVariant(e.eventType)" class="ev-badge">{{ e.eventType }}</AppBadge>
                <span class="mono text-sm">{{ e.deviceId }}</span>
                <span class="muted text-xs">{{ fmt(e.uploadedAt) }}</span>
              </li>
              <li v-if="!recentEvents.length" class="empty">Sem eventos recentes</li>
            </ul>
          </AppCard>

          <AppCard title="Erros Recentes" class="panel">
            <ul class="activity-list">
              <li v-for="e in recentErrors" :key="e.id" class="activity-item">
                <AppBadge variant="danger" class="ev-badge">{{ e.errorCode }}</AppBadge>
                <span class="mono text-sm">{{ e.deviceId }}</span>
                <span class="muted text-xs">{{ fmt(e.timestamp) }}</span>
              </li>
              <li v-if="!recentErrors.length" class="empty">Sem erros recentes</li>
            </ul>
          </AppCard>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.loading { color: var(--text-muted); padding: var(--space-8); text-align: center; }
.dashboard { display: flex; flex-direction: column; gap: var(--space-6); }
.stat-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: var(--space-4); }
.panels { display: grid; grid-template-columns: 1fr 360px; gap: var(--space-4); }
.side-panels { display: flex; flex-direction: column; gap: var(--space-4); }
.panel { height: fit-content; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 0 var(--space-3); text-align: left; }
.tbl td { padding: var(--space-2) 0; font-size: var(--text-sm); border-top: 1px solid var(--border); }
.tbl-row { cursor: pointer; transition: background var(--transition); }
.tbl-row:hover td { background: var(--panel); }

.activity-list { list-style: none; display: flex; flex-direction: column; gap: var(--space-3); }
.activity-item { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
.ev-badge { flex-shrink: 0; }
.empty { color: var(--text-muted); font-size: var(--text-sm); }
.mono { font-family: var(--font-mono); }
.muted { color: var(--text-muted); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .panels { grid-template-columns: 1fr; }
}
</style>
