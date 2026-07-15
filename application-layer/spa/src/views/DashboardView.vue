<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import DeviceMap from '@/components/DeviceMap.vue'
import StatCard from '@/components/StatCard.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import EventActivityList from '@/components/EventActivityList.vue'
import ErrorActivityList from '@/components/ErrorActivityList.vue'
import LiveIndicator from '@/components/LiveIndicator.vue'
import { devicesApi } from '@/services/devices'
import { eventsApi } from '@/services/events'
import { errorsApi } from '@/services/errors'
import { useLiveStateStore } from '@/stores/liveState'
import type {
  DeviceSummaryDTO,
  DeviceStatus,
  EventRegistryResponseDTO,
  ErrorRecordResponseDTO,
} from '@/types/models'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

const liveStore = useLiveStateStore()
const devices = ref<DeviceSummaryDTO[]>([])
const recentEvents = ref<EventRegistryResponseDTO[]>([])
const recentErrors = ref<ErrorRecordResponseDTO[]>([])
const totalEvents = ref(0)
const totalErrors = ref(0)
const loading = ref(true)

const mergedDevices = computed(() =>
  devices.value.map((d) => {
    const lv = liveStore.devices.get(d.deviceId)
    if (!lv) return d
    return {
      ...d,
      status: (lv.status as DeviceStatus) ?? d.status,
      lastSeen: lv.lastSeen ?? d.lastSeen,
    }
  }),
)

const totalDevices = computed(() => mergedDevices.value.length)
const activeDevices = computed(() => mergedDevices.value.filter(d => d.status === 'ACTIVE').length)
const pendingDevices = computed(() => mergedDevices.value.filter(d => (['PENDING', 'PROVISIONING'] as DeviceStatus[]).includes(d.status)).length)

const statusVariant = (s: DeviceStatus): BadgeVariant => {
  const map: Record<string, BadgeVariant> = {
    ACTIVE: 'success', PENDING: 'warning', PROVISIONING: 'info',
    COMMAND_PENDING: 'info', REVOKED: 'danger', ERROR: 'danger',
  }
  return map[s] ?? 'muted'
}

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'


const MINIMAP_STORAGE_KEY = 'dashboard_minimap_config'
const ALL_STATUSES: DeviceStatus[] = ['ACTIVE', 'PENDING', 'PROVISIONING', 'COMMAND_PENDING', 'REVOKED', 'ERROR']

interface MinimapConfig {
  statusFilter: string[]
  autoFit: boolean
}

const loadMinimapConfig = (): MinimapConfig => {
  try {
    const raw = localStorage.getItem(MINIMAP_STORAGE_KEY)
    if (raw) {
      const c = JSON.parse(raw)
      return {
        statusFilter: Array.isArray(c.statusFilter) ? c.statusFilter : [],
        autoFit: c.autoFit !== false,
      }
    }
  } catch { /* config corrompida — usa default */ }
  return { statusFilter: [], autoFit: true }
}

const minimapConfig = ref<MinimapConfig>(loadMinimapConfig())
const showMinimapConfig = ref(false)

watch(minimapConfig, (c) => localStorage.setItem(MINIMAP_STORAGE_KEY, JSON.stringify(c)), { deep: true })

const toggleMinimapStatus = (s: string) => {
  const i = minimapConfig.value.statusFilter.indexOf(s)
  if (i >= 0) minimapConfig.value.statusFilter.splice(i, 1)
  else minimapConfig.value.statusFilter.push(s)
}

onMounted(async () => {
  try {
    const [devList, evRes, errRes] = await Promise.all([
      devicesApi.listAll(),
      eventsApi.list(0),
      errorsApi.list(0),
    ])
    devices.value = devList
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

      <AppCard title="Mapa dos Dispositivos" class="panel">
        <template #actions>
          <div class="minimap-actions">
            <LiveIndicator />
            <button class="icon-btn" title="Configurar minimapa" @click="showMinimapConfig = !showMinimapConfig">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="3"/>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>
              </svg>
            </button>
            <router-link to="/map" class="map-full-link">Mapa completo →</router-link>
          </div>
        </template>

        <div v-if="showMinimapConfig" class="minimap-config">
          <div class="chips">
            <button
              class="chip"
              :class="{ active: !minimapConfig.statusFilter.length }"
              @click="minimapConfig.statusFilter = []"
            >Todos</button>
            <button
              v-for="s in ALL_STATUSES"
              :key="s"
              class="chip"
              :class="{ active: minimapConfig.statusFilter.includes(s) }"
              :data-status="s"
              @click="toggleMinimapStatus(s)"
            >
              <span class="chip-dot" />
              {{ s }}
            </button>
          </div>
          <label class="autofit-toggle">
            <input v-model="minimapConfig.autoFit" type="checkbox" />
            Auto-ajustar zoom
          </label>
        </div>

        <div class="minimap">
          <DeviceMap
            :status-filter="minimapConfig.statusFilter"
            :auto-fit="minimapConfig.autoFit"
            :scroll-wheel-zoom="false"
          />
        </div>
      </AppCard>

      <div class="panels">
        <AppCard title="Dispositivos Recentes" class="panel">
          <template #actions>
            <LiveIndicator />
          </template>
          <table class="tbl">
            <thead><tr><th>Nome</th><th>Status</th><th>Firmware</th><th>Última vez visto</th></tr></thead>
            <tbody>
              <tr v-for="d in mergedDevices.slice(0, 8)" :key="d.deviceId" class="tbl-row"
                  @click="$router.push(`/devices/${d.deviceId}`)">
                <td class="mono">{{ d.name }}</td>
                <td><AppBadge :variant="statusVariant(d.status)" dot>{{ d.status }}</AppBadge></td>
                <td class="mono muted">{{ d.firmwareVersion ? `v${d.firmwareVersion}` : '—' }}</td>
                <td class="muted">{{ fmt(d.lastSeen) }}</td>
              </tr>
              <tr v-if="!devices.length"><td colspan="4" class="empty">Nenhum dispositivo</td></tr>
            </tbody>
          </table>
        </AppCard>

        <div class="side-panels">
          <AppCard title="Eventos Recentes" class="panel">
            <EventActivityList :events="recentEvents" />
          </AppCard>

          <AppCard title="Erros Recentes" class="panel">
            <ErrorActivityList :errors="recentErrors" />
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

/* ─── Minimapa ──────────────────────────────────────── */
.minimap { height: 340px; }

.minimap-actions {
  display: flex;
  align-items: center;
  gap: var(--space-3);
}

.icon-btn {
  width: 26px;
  height: 26px;
  border-radius: var(--radius-md);
  background: transparent;
  border: 1px solid var(--border);
  color: var(--text-muted);
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all var(--transition);
}
.icon-btn:hover { background: var(--panel); color: var(--text); }

.map-full-link {
  font-size: var(--text-xs);
  color: var(--primary);
  text-decoration: none;
  white-space: nowrap;
}
.map-full-link:hover { text-decoration: underline; }

.minimap-config {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
  padding-bottom: var(--space-3);
  margin-bottom: var(--space-3);
  border-bottom: 1px solid var(--border);
}

.chips { display: flex; gap: var(--space-2); flex-wrap: wrap; }

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-sans);
  font-size: var(--text-xs);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition);
}
.chip:hover { color: var(--text); background: var(--panel); }
.chip.active {
  color: var(--primary);
  background: var(--primary-dim);
  border-color: rgba(6, 182, 212, 0.3);
}

.chip-dot { width: 7px; height: 7px; border-radius: 50%; background: #6b7280; }
.chip[data-status="ACTIVE"] .chip-dot { background: #22c55e; }
.chip[data-status="PENDING"] .chip-dot { background: #eab308; }
.chip[data-status="PROVISIONING"] .chip-dot,
.chip[data-status="COMMAND_PENDING"] .chip-dot { background: #3b82f6; }
.chip[data-status="REVOKED"] .chip-dot,
.chip[data-status="ERROR"] .chip-dot { background: #ef4444; }

.autofit-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--text-muted);
  cursor: pointer;
  white-space: nowrap;
}

.empty { color: var(--text-muted); font-size: var(--text-sm); }
.mono { font-family: var(--font-mono); }
.muted { color: var(--text-muted); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }

@media (max-width: 1100px) {
  .stat-grid { grid-template-columns: repeat(2, 1fr); }
  .panels { grid-template-columns: 1fr; }
}

@media (max-width: 540px) {
  .stat-grid { grid-template-columns: 1fr; }
}
</style>
