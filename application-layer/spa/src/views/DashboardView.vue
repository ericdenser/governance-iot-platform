<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppLayout from '@/components/AppLayout.vue'
import DeviceMap from '@/components/DeviceMap.vue'
import StatCard from '@/components/StatCard.vue'
import AppCard from '@/components/AppCard.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import BatteryBar from '@/components/BatteryBar.vue'
import EventActivityList from '@/components/EventActivityList.vue'
import ErrorActivityList from '@/components/ErrorActivityList.vue'
import TelemetryChart from '@/components/TelemetryChart.vue'
import ShowMoreToggle from '@/components/ShowMoreToggle.vue'
import { useFeed } from '@/composables/useFeed'
import { devicesApi } from '@/services/devices'
import { eventsApi } from '@/services/events'
import { errorsApi } from '@/services/errors'
import { useLiveStateStore } from '@/stores/liveState'
import { CONN_STATES, connState } from '@/utils/connection'
import { absoluteTime, relativeTime } from '@/utils/format'
import { statusText } from '@/utils/statusLabels'
import type {
  DeviceHealth,
  DeviceSummaryDTO,
  EventRegistryResponseDTO,
  ErrorRecordResponseDTO,
} from '@/types/models'


const liveStore = useLiveStateStore()
const devices = ref<DeviceSummaryDTO[]>([])
const loading = ref(true)

const LIST_STEP = 10
const isLast = (p: { number: number; totalPages: number } | undefined) => !p || p.number + 1 >= p.totalPages

const eventsFeed = useFeed<EventRegistryResponseDTO>(async (page) => {
  const r = await eventsApi.list(page)
  return { items: r.data.content ?? [], last: isLast(r.data.page) }
}, LIST_STEP)

const errorsFeed = useFeed<ErrorRecordResponseDTO>(async (page) => {
  const r = await errorsApi.list(page)
  return { items: r.data.content ?? [], last: isLast(r.data.page) }
}, LIST_STEP)

// Devices are all loaded already; only the visible slice grows
const devicesShown = ref(LIST_STEP)

const mergedDevices = computed(() =>
  devices.value.map((d) => {
    const lv = liveStore.devices.get(d.deviceId)
    if (!lv) return d
    // status vem do DB; live so atualiza metricas e health
    return {
      ...d,
      health: (lv.health as DeviceHealth | undefined) ?? d.health,
      lastSeen: lv.lastSeen ?? d.lastSeen,
      batteryMv: lv.batteryMv ?? d.batteryMv,
      batteryTs: lv.batteryTs ?? d.batteryTs,
    }
  }),
)

const recentDevices = computed(() =>
  [...mergedDevices.value].sort((a, b) => (b.lastSeen ?? '').localeCompare(a.lastSeen ?? '')))

const totalDevices = computed(() => mergedDevices.value.length)
const onlineDevices = computed(() => mergedDevices.value.filter(d => connState(d.status, d.lastSeen) === 'online').length)
const warningDevices = computed(() => mergedDevices.value.filter(d => d.health === 'WARNING').length)

const MINIMAP_STORAGE_KEY = 'dashboard_minimap_config'

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
        // Old configs stored device statuses; keep only connection states
        statusFilter: Array.isArray(c.statusFilter) ? c.statusFilter.filter((x: string) => (CONN_STATES as string[]).includes(x)) : [],
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
    const [devList] = await Promise.all([
      devicesApi.listAll(),
      eventsFeed.reset(),
      errorsFeed.reset(),
    ])
    devices.value = devList
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <AppLayout>
    <AppSkeleton v-if="loading" variant="cards" :rows="4" />
    <div v-else class="dashboard">

      <div class="stat-grid">
        <StatCard label="Dispositivos" :value="totalDevices" />
        <StatCard label="Online agora" :value="onlineDevices" sub="último sinal há até 5 min" />
        <StatCard label="Com alerta" :value="warningDevices" variant="warning" :highlight="warningDevices > 0" />
      </div>

      <TelemetryChart :devices="mergedDevices" />

      <!-- Card inteiro leva ao mapa completo; mapa e filtros param o clique -->
      <AppCard title="Mapa dos dispositivos" class="panel map-card" role="link" tabindex="0"
               @click="$router.push('/map')" @keydown.enter="$router.push('/map')">
        <template #actions>
          <div class="minimap-actions" @click.stop>
            <button class="icon-btn" title="Configurar minimapa" aria-label="Configurar minimapa" :aria-expanded="showMinimapConfig" @click="showMinimapConfig = !showMinimapConfig">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <circle cx="12" cy="12" r="3"/>
                <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>
              </svg>
            </button>
          </div>
        </template>

        <div v-if="showMinimapConfig" class="minimap-config" @click.stop>
          <div class="chips">
            <button
              class="chip"
              :class="{ active: !minimapConfig.statusFilter.length }"
              @click="minimapConfig.statusFilter = []"
            >Todos</button>
            <button
              v-for="s in CONN_STATES"
              :key="s"
              class="chip"
              :class="{ active: minimapConfig.statusFilter.includes(s) }"
              :data-status="s"
              @click="toggleMinimapStatus(s)"
            >
              <span class="chip-dot" />
              {{ statusText('conn', s) }}
            </button>
          </div>
          <label class="autofit-toggle">
            <input v-model="minimapConfig.autoFit" type="checkbox" />
            Auto-ajustar zoom
          </label>
        </div>

        <div class="minimap" @click.stop>
          <DeviceMap
            :status-filter="minimapConfig.statusFilter"
            :auto-fit="minimapConfig.autoFit"
            :scroll-wheel-zoom="false"
          />
        </div>
      </AppCard>

      <div class="panels">
        <AppCard title="Dispositivos recentes" class="panel">
          <table class="tbl">
            <thead><tr><th>Nome</th><th>Status</th><th>Firmware</th><th>Bateria</th><th>Último sinal</th></tr></thead>
            <tbody>
              <tr v-for="d in recentDevices.slice(0, devicesShown)" :key="d.deviceId" class="tbl-row"
                  @click="$router.push(`/devices/${d.deviceId}`)">
                <td>{{ d.name }}</td>
                <td><StatusLabel kind="conn" :code="connState(d.status, d.lastSeen)" /></td>
                <td class="mono muted">{{ d.firmwareVersion ? `v${d.firmwareVersion}` : '—' }}</td>
                <td><BatteryBar :mv="d.batteryMv" :ts="d.batteryTs" :device-status="d.status" /></td>
                <td class="muted" :title="absoluteTime(d.lastSeen)">{{ relativeTime(d.lastSeen) }}</td>
              </tr>
              <tr v-if="!devices.length"><td colspan="5"><EmptyState compact title="Nenhum dispositivo" hint="Provisione o primeiro na tela de Provisionamento." /></td></tr>
            </tbody>
          </table>
          <ShowMoreToggle
            :can-more="recentDevices.length > devicesShown"
            :can-less="devicesShown > LIST_STEP"
            @more="devicesShown += LIST_STEP"
            @less="devicesShown = LIST_STEP" />
        </AppCard>

        <div class="side-panels">
          <AppCard title="Eventos recentes" class="panel">
            <EventActivityList :events="eventsFeed.visible.value" />
            <ShowMoreToggle
              :can-more="eventsFeed.canMore.value" :can-less="eventsFeed.canLess.value"
              :loading="eventsFeed.loading.value"
              @more="eventsFeed.more" @less="eventsFeed.less" />
          </AppCard>

          <AppCard title="Erros recentes" class="panel">
            <ErrorActivityList :errors="errorsFeed.visible.value" />
            <ShowMoreToggle
              :can-more="errorsFeed.canMore.value" :can-less="errorsFeed.canLess.value"
              :loading="errorsFeed.loading.value"
              @more="errorsFeed.more" @less="errorsFeed.less" />
          </AppCard>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.dashboard { display: flex; flex-direction: column; gap: var(--space-6); }
.stat-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: var(--space-3); }
.panels { display: flex; flex-direction: column; gap: var(--space-4); }
.side-panels { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-4); align-items: start; }
.panel { height: fit-content; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: 0 0 var(--space-3); text-align: left; }
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

.map-card { cursor: pointer; transition: border-color var(--transition); }
.map-card:hover, .map-card:focus-visible { border-color: var(--border-strong); }
.map-card :deep(.card-title)::after { content: ' →'; color: var(--text-muted); transition: color var(--transition); }
.map-card:hover :deep(.card-title)::after { color: var(--accent); }
.minimap { cursor: default; }

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
  border-radius: var(--radius-sm);
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
  border-color: var(--primary-border);
}

.chip-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--status-unknown); }
.chip[data-status="online"] .chip-dot { background: var(--status-active); }
.chip[data-status="offline"] .chip-dot { background: var(--status-unknown); }
.chip[data-status="pending"] .chip-dot { background: var(--status-pending); }

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

@media (max-width: 900px) {
  .side-panels { grid-template-columns: 1fr; }
}

@media (max-width: 540px) {
  .stat-grid { grid-template-columns: 1fr; }
}
</style>
