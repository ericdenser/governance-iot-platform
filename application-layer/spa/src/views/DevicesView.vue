<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppButton from '@/components/AppButton.vue'
import { devicesApi } from '@/services/devices'
import { useAuthStore } from '@/stores/auth'
import { useLiveStateStore } from '@/stores/liveState'
import { useDebouncedRef } from '@/composables/useDebouncedRef'
import StatusLabel from '@/components/StatusLabel.vue'
import { isOnline } from '@/utils/connection'
import { absoluteTime, relativeTime } from '@/utils/format'
import type { DeviceHealth, DeviceSummaryDTO } from '@/types/models'

// ── Connection state (client-side, so pelo lastSeen) ─────────────────────
type ConnState = 'online' | 'offline' | 'pending'
type DeviceFilter = 'all' | 'online' | 'offline' | 'warning'
const FILTER_LABEL: Record<DeviceFilter, string> = { all: 'Todos', online: 'Online', offline: 'Offline', warning: 'Com alerta' }

type MergedDevice = DeviceSummaryDTO & {
  lat?: number | null
  lon?: number | null
  rssi?: number | null
  tempC?: number | null
  health: DeviceHealth
  healthReason: string | null
}

// ── State ────────────────────────────────────────────────────────────────
const authStore = useAuthStore()
const liveStore = useLiveStateStore()
const router = useRouter()

const devices = ref<DeviceSummaryDTO[]>([])
const loading = ref(true)
const search = ref('')
const debouncedSearch = useDebouncedRef(search, 300)
const connFilter = ref<DeviceFilter>('all')

const page = ref(0)
const size = ref(60)
const totalPages = ref(0)
const totalElements = ref(0)

// ── Merge com live state (SSE) + campos derivados ────────────────────────
const mergedDevices = computed<MergedDevice[]>(() =>
  devices.value.map((d) => {
    const lv = liveStore.devices.get(d.deviceId)
    if (!lv) return {
      ...d,
      lat: null,
      lon: null,
      rssi: d.rssi,
      tempC: d.tempC,
    }
    // status vem sempre do DB (fonte de verdade — handlers HTTP escrevem la
    // mas nao invalidam o Redis hot state). Live so sobrescreve metricas.
    return {
      ...d,
      lastSeen: lv.lastSeen ?? d.lastSeen,
      batteryMv: lv.batteryMv ?? d.batteryMv,
      batteryTs: lv.batteryTs ?? d.batteryTs,
      lat: lv.lat ?? null,
      lon: lv.lon ?? null,
      rssi: lv.rssi ?? d.rssi,
      tempC: lv.tempC ?? d.tempC,
      health: (lv.health as DeviceHealth | undefined) ?? d.health,
      healthReason: lv.healthReason !== undefined ? lv.healthReason || null : d.healthReason,
    }
  }),
)

// ── Helpers de connection state ──────────────────────────────────────────
const connStateOf = (d: MergedDevice): ConnState => {
  if (d.status === 'PENDING' || d.status === 'PROVISIONING') return 'pending'
  if (d.status === 'REVOKED') return 'offline'

  return isOnline(d.lastSeen) ? 'online' : 'offline'
}

const isWarning = (d: MergedDevice): boolean => d.health === 'WARNING'

const hasReason = (d: MergedDevice, reason: string): boolean =>
  (d.healthReason ?? '').split(',').includes(reason)

const REASON_LABEL: Record<string, string> = {
  LOW_BATTERY: 'Bateria baixa',
  HIGH_TEMP: 'Temperatura alta',
}

const reasonLabel = (d: MergedDevice): string =>
  (d.healthReason ?? '').split(',').filter(Boolean).map(r => REASON_LABEL[r] ?? r).join(' · ')

// ── Formatadores de métricas ─────────────────────────────────────────────
const fmtBattery = (mv: number | null | undefined): string => {
  if (mv == null) return '—'
  // 3.0v = 0%, 4.2v = 100% (LiPo típico). Ajuste conforme sua fonte.
  const pct = Math.max(0, Math.min(100, Math.round(((mv - 3000) / (4200 - 3000)) * 100)))
  return `${pct}%`
}

// Cor de bateria/temperatura segue o health do backend
const batteryClass = (d: MergedDevice): string => {
  if (d.batteryMv == null) return 'metric-muted'
  return hasReason(d, 'LOW_BATTERY') ? 'metric-warn' : ''
}

const fmtSignal = (rssi: number | null | undefined): string => {
  if (rssi == null) return '—'
  // RSSI típico WiFi: -30 (excelente) a -90 (péssimo). Converte pra %.
  const pct = Math.max(0, Math.min(100, Math.round(2 * (rssi + 100))))
  return `${pct}%`
}

const signalClass = (rssi: number | null | undefined): string => {
  if (rssi == null) return 'metric-muted'
  if (rssi < -80) return 'metric-danger'
  if (rssi < -70) return 'metric-warn'
  return ''
}

const fmtTemp = (c: number | null | undefined): string => {
  if (c == null) return '—'
  return `${Math.round(c)}°C`
}

const tempClass = (d: MergedDevice): string => {
  if (d.tempC == null) return 'metric-muted'
  return hasReason(d, 'HIGH_TEMP') ? 'metric-warn' : ''
}

const fmtLocation = (lat: number | null | undefined, lon: number | null | undefined): string => {
  if (lat == null || lon == null) return '—'
  return `${lat.toFixed(2)}, ${lon.toFixed(2)}`
}

// ── Filter + contadores pro header ───────────────────────────────────────
const visibleDevices = computed<MergedDevice[]>(() => {
  const f = connFilter.value
  if (f === 'all') return mergedDevices.value
  if (f === 'warning') return mergedDevices.value.filter(isWarning)
  return mergedDevices.value.filter(d => connStateOf(d) === f)
})

const counts = computed(() => {
  const c = { online: 0, offline: 0, pending: 0, warning: 0 }
  for (const d of mergedDevices.value) {
    c[connStateOf(d)]++
    if (isWarning(d)) c.warning++
  }
  return c
})

// ── Loader ────────────────────────────────────────────────────────────────
const load = async (silent = false) => {
  if (!silent) loading.value = true
  try {
    const r = await devicesApi.list({
      page: page.value,
      size: size.value,
      search: debouncedSearch.value.trim() || undefined,
    })
    devices.value = r.data.content
    totalPages.value = r.data.page.totalPages
    totalElements.value = r.data.page.totalElements
  } finally {
    loading.value = false
  }
}

watch(debouncedSearch, () => { page.value = 0; load() })

// SSE com status diferente do DB: recarrega a lista depois que o webhook grava
const RELOAD_DELAY_MS = 2000
let reloadTimer: ReturnType<typeof setTimeout> | null = null

const liveDivergence = computed(() =>
  devices.value
    .filter(d => {
      const lv = liveStore.devices.get(d.deviceId)
      return lv?.status != null && lv.status !== d.status
    })
    .map(d => `${d.deviceId}:${liveStore.devices.get(d.deviceId)?.status}`)
    .join(','),
)

watch(liveDivergence, (sig) => {
  if (!sig || reloadTimer) return
  reloadTimer = setTimeout(() => { reloadTimer = null; load(true) }, RELOAD_DELAY_MS)
})

onUnmounted(() => { if (reloadTimer) clearTimeout(reloadTimer) })

const goToPage = (n: number) => {
  if (n < 0 || n >= totalPages.value) return
  page.value = n
  load()
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">Dispositivos</h2>
        <p class="page-sub">{{ totalElements }} registrados · {{ counts.online }} online · {{ counts.warning }} com alerta</p>
      </div>
      <div class="header-right">
        <AppButton v-if="authStore.isAdmin" variant="primary" size="md"
                   @click="router.push('/firmware')">
          Provisionar
        </AppButton>
      </div>
    </div>

    <div class="toolbar">
      <div class="search-wrap">
        <svg class="search-icon" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
        <input v-model="search" class="search" placeholder="Buscar por nome, ID ou MAC..." />
      </div>
      <div class="filter-group">
        <button v-for="f in (['all','online','warning','offline'] as const)" :key="f"
          class="filter-btn"
          :class="{ active: connFilter === f }"
          @click="connFilter = f">
          {{ FILTER_LABEL[f] }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="empty">Carregando...</div>
    <div v-else-if="!visibleDevices.length" class="empty">
      {{ connFilter === 'all' ? 'Nenhum dispositivo encontrado' : `Nenhum dispositivo em "${FILTER_LABEL[connFilter].toLowerCase()}"` }}
    </div>
    <div v-else class="dev-grid">
      <div v-for="d in visibleDevices" :key="d.deviceId"
        class="dev-card"
        :class="`state-${connStateOf(d)}`"
        @click="router.push(`/devices/${d.deviceId}`)">

        <div class="card-top">
          <div class="card-title-block">
            <h3 class="card-title">{{ d.name }}</h3>
            <p class="card-sub mono">
              {{ d.deviceId.slice(0, 8) }}<span v-if="d.firmwareName"> · {{ d.firmwareName }}</span>
            </p>
          </div>
          <StatusLabel kind="conn" :code="connStateOf(d)" class="conn" />
        </div>

        <div class="metrics">
          <div class="metric">
            <span class="metric-label">Sinal</span>
            <span class="metric-value" :class="signalClass(d.rssi)">{{ fmtSignal(d.rssi) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">Temperatura</span>
            <span class="metric-value" :class="tempClass(d)">{{ fmtTemp(d.tempC) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label">Bateria</span>
            <span class="metric-value" :class="batteryClass(d)">{{ fmtBattery(d.batteryMv) }}</span>
          </div>
        </div>

        <StatusLabel v-if="d.status === 'COMMAND_PENDING' || d.status === 'OTA_PENDING'" kind="device" :code="d.status" />
        <StatusLabel v-if="isWarning(d)" kind="health" code="WARNING" :label="reasonLabel(d) || undefined" />

        <div class="card-footer">
          <span class="footer-loc">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
            <span class="mono">{{ fmtLocation(d.lat, d.lon) }}</span>
          </span>
          <span class="footer-time" :title="absoluteTime(d.lastSeen)">{{ relativeTime(d.lastSeen) }}</span>
        </div>
      </div>
    </div>

    <div v-if="!loading && totalPages > 1" class="pager">
      <AppButton size="sm" :disabled="page === 0" @click="goToPage(page - 1)">Anterior</AppButton>
      <span class="pager-info text-muted">Página {{ page + 1 }} de {{ totalPages }}</span>
      <AppButton size="sm" :disabled="page + 1 >= totalPages" @click="goToPage(page + 1)">Próxima</AppButton>
    </div>
  </AppLayout>
</template>

<style scoped>
/* ── Page header ─────────────────────────────────────────────────────── */
.page-header { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-4); margin-bottom: var(--space-4); flex-wrap: wrap; }
.header-left { display: flex; flex-direction: column; gap: 4px; }
.page-title { font-family: var(--font-sans); font-size: var(--text-xl); font-weight: 600; color: var(--text); margin: 0; }
.page-sub { font-size: var(--text-xs); color: var(--text-muted); margin: 0; }
.header-right { display: flex; align-items: center; gap: var(--space-2); }

/* ── Toolbar ─────────────────────────────────────────────────────────── */
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); margin-bottom: var(--space-4); flex-wrap: wrap; }
.search-wrap { position: relative; flex: 1; max-width: 320px; min-width: 200px; }
.search-icon { position: absolute; left: 10px; top: 50%; transform: translateY(-50%); color: var(--text-muted); pointer-events: none; }
.search { width: 100%; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 7px 12px 7px 32px; font-size: var(--text-sm); color: var(--text); outline: none; font-family: var(--font-sans); box-sizing: border-box; }
.search:focus { border-color: var(--primary); }

.filter-group { display: flex; gap: var(--space-1); }
.filter-btn { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 5px 12px; font-size: var(--text-sm); color: var(--text-secondary); cursor: pointer; transition: border-color var(--transition), color var(--transition); }
.filter-btn:hover { color: var(--text); }
.filter-btn.active { border-color: var(--accent-border); color: var(--accent); background: var(--accent-dim); }

/* ── Grid de cards ───────────────────────────────────────────────────── */
.dev-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: var(--space-3); }

.dev-card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); cursor: pointer; display: flex; flex-direction: column; gap: var(--space-3); transition: border-color var(--transition), transform var(--transition); }
.dev-card:hover { border-color: var(--border-strong); }
.dev-card.state-offline { opacity: 0.72; }

/* ── Card top: title + badge ─────────────────────────────────────────── */
.card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-2); }
.card-title-block { min-width: 0; flex: 1; }
.card-title { font-family: var(--font-sans); font-size: var(--text-md); font-weight: 600; color: var(--text); margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-sub { font-size: var(--text-xs); color: var(--text-muted); margin: 2px 0 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.conn { flex-shrink: 0; font-size: var(--text-xs); }

/* ── Métricas ────────────────────────────────────────────────────────── */
.metrics { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--space-3); }
.metric { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.metric-label { font-size: var(--text-xs); color: var(--text-muted); }
.metric-value { font-size: var(--text-md); font-weight: 500; color: var(--text); font-variant-numeric: tabular-nums; }
.metric-value.metric-muted { color: var(--text-muted); }
.metric-value.metric-warn { color: var(--warning); }
.metric-value.metric-danger { color: var(--danger); }

/* ── Card footer ─────────────────────────────────────────────────────── */
.card-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); padding-top: var(--space-3); border-top: 1px solid var(--border); font-size: var(--text-xs); color: var(--text-muted); }
.footer-loc { display: inline-flex; align-items: center; gap: 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.footer-loc svg { flex-shrink: 0; }
.footer-time { flex-shrink: 0; }

/* ── Estados vazios / pager ──────────────────────────────────────────── */
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; font-size: var(--text-sm); }
.mono { font-family: var(--font-mono); }
.text-muted { color: var(--text-muted); }

.pager { display: flex; align-items: center; justify-content: center; gap: var(--space-4); margin-top: var(--space-6); }
.pager-info { min-width: 140px; text-align: center; font-size: var(--text-xs); }
</style>
