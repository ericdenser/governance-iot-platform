<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppButton from '@/components/AppButton.vue'
import LiveIndicator from '@/components/LiveIndicator.vue'
import { devicesApi } from '@/services/devices'
import { useAuthStore } from '@/stores/auth'
import { useLiveStateStore } from '@/stores/liveState'
import { useDebouncedRef } from '@/composables/useDebouncedRef'
import type { DeviceSummaryDTO, DeviceStatus } from '@/types/models'

// ── Derivação de connection state (client-side) ──────────────────────────
// Backend so tem lastSeen + status de negocio. Derivo online/warning/offline
// combinando os dois pra alimentar filtros + badge dos cards.
const ONLINE_MAX_S  = 5  * 60
const WARNING_MAX_S = 30 * 60

type ConnState = 'online' | 'warning' | 'offline' | 'pending'

type MergedDevice = DeviceSummaryDTO & {
  lat?: number | null
  lon?: number | null
  rssi?: number | null
  tempC?: number | null
}

// ── State ────────────────────────────────────────────────────────────────
const authStore = useAuthStore()
const liveStore = useLiveStateStore()
const router = useRouter()

const devices = ref<DeviceSummaryDTO[]>([])
const loading = ref(true)
const search = ref('')
const debouncedSearch = useDebouncedRef(search, 300)
const connFilter = ref<'all' | ConnState>('all')

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
    return {
      ...d,
      status: (lv.status as DeviceStatus) ?? d.status,
      lastSeen: lv.lastSeen ?? d.lastSeen,
      batteryMv: lv.batteryMv ?? d.batteryMv,
      batteryTs: lv.batteryTs ?? d.batteryTs,
      lat: lv.lat ?? null,
      lon: lv.lon ?? null,
      rssi: lv.rssi ?? d.rssi,
      tempC: lv.tempC ?? d.tempC,
    }
  }),
)

// ── Helpers de connection state ──────────────────────────────────────────
const connStateOf = (d: MergedDevice): ConnState => {
  if (d.status === 'PENDING' || d.status === 'PROVISIONING' || d.status === 'COMMAND_PENDING') {
    return 'pending'
  }
  if (d.status === 'REVOKED') return 'offline'
  if (d.status === 'ERROR' || d.status === 'CRITICAL_BATTERY') return 'warning'

  const age = ageSeconds(d.lastSeen)
  if (age === null) return 'offline'
  if (age <= ONLINE_MAX_S && d.status === 'ACTIVE') return 'online'
  if (age <= WARNING_MAX_S) return 'warning'
  return 'offline'
}

const ageSeconds = (iso: string | null | undefined): number | null => {
  if (!iso) return null
  const t = new Date(iso).getTime()
  if (!Number.isFinite(t)) return null
  return Math.max(0, Math.floor((Date.now() - t) / 1000))
}

const relativeAge = (iso: string | null | undefined): string => {
  const s = ageSeconds(iso)
  if (s === null) return '—'
  if (s < 60) return `${s}s`
  if (s < 3600) return `${Math.floor(s / 60)}m`
  if (s < 86400) return `${Math.floor(s / 3600)}h`
  return `${Math.floor(s / 86400)}d`
}

// ── Formatadores de métricas ─────────────────────────────────────────────
const fmtBattery = (mv: number | null | undefined): string => {
  if (mv == null) return '—'
  // 3.0v = 0%, 4.2v = 100% (LiPo típico). Ajuste conforme sua fonte.
  const pct = Math.max(0, Math.min(100, Math.round(((mv - 3000) / (4200 - 3000)) * 100)))
  return `${pct}%`
}

const batteryClass = (mv: number | null | undefined): string => {
  if (mv == null) return 'metric-muted'
  const pct = ((mv - 3000) / (4200 - 3000)) * 100
  if (pct < 25) return 'metric-danger'
  if (pct < 50) return 'metric-warn'
  return ''
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

const tempClass = (c: number | null | undefined): string => {
  if (c == null) return 'metric-muted'
  if (c > 55) return 'metric-danger'
  if (c > 45) return 'metric-warn'
  return ''
}

const fmtLocation = (lat: number | null | undefined, lon: number | null | undefined): string => {
  if (lat == null || lon == null) return '—'
  return `${lat.toFixed(2)}, ${lon.toFixed(2)}`
}

// ── Filter + contadores pro header ───────────────────────────────────────
const visibleDevices = computed<MergedDevice[]>(() => {
  if (connFilter.value === 'all') return mergedDevices.value
  return mergedDevices.value.filter(d => connStateOf(d) === connFilter.value)
})

const counts = computed(() => {
  const c = { online: 0, warning: 0, offline: 0, pending: 0 }
  for (const d of mergedDevices.value) c[connStateOf(d)]++
  return c
})

// ── Loader ────────────────────────────────────────────────────────────────
const load = async () => {
  loading.value = true
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
        <p class="page-sub mono">{{ totalElements }} registrados · {{ counts.online }} online · {{ counts.warning }} alertas</p>
      </div>
      <div class="header-right">
        <LiveIndicator />
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
          {{ f }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="empty">Carregando...</div>
    <div v-else-if="!visibleDevices.length" class="empty">
      {{ connFilter === 'all' ? 'Nenhum dispositivo encontrado' : `Nenhum dispositivo ${connFilter}` }}
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
          <span class="state-badge mono" :class="`badge-${connStateOf(d)}`">
            {{ connStateOf(d).toUpperCase() }}
          </span>
        </div>

        <div class="metrics">
          <div class="metric">
            <span class="metric-label mono">Signal</span>
            <span class="metric-value" :class="signalClass(d.rssi)">{{ fmtSignal(d.rssi) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label mono">Temp</span>
            <span class="metric-value" :class="tempClass(d.tempC)">{{ fmtTemp(d.tempC) }}</span>
          </div>
          <div class="metric">
            <span class="metric-label mono">Battery</span>
            <span class="metric-value" :class="batteryClass(d.batteryMv)">{{ fmtBattery(d.batteryMv) }}</span>
          </div>
        </div>

        <div class="card-footer">
          <span class="footer-loc">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 10c0 7-9 13-9 13s-9-6-9-13a9 9 0 0 1 18 0z"/><circle cx="12" cy="10" r="3"/></svg>
            <span class="mono">{{ fmtLocation(d.lat, d.lon) }}</span>
          </span>
          <span class="footer-time mono">{{ relativeAge(d.lastSeen) }}</span>
        </div>
      </div>
    </div>

    <div v-if="!loading && totalPages > 1" class="pager">
      <AppButton size="sm" :disabled="page === 0" @click="goToPage(page - 1)">Anterior</AppButton>
      <span class="pager-info mono text-muted">Página {{ page + 1 }} de {{ totalPages }}</span>
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
.search { width: 100%; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 7px 12px 7px 32px; font-size: var(--text-sm); color: var(--text); outline: none; font-family: var(--font-mono); box-sizing: border-box; }
.search:focus { border-color: var(--primary); }

.filter-group { display: flex; gap: var(--space-1); }
.filter-btn { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 5px 12px; font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); cursor: pointer; text-transform: lowercase; transition: border-color var(--transition), color var(--transition); }
.filter-btn:hover { color: var(--text); }
.filter-btn.active { border-color: var(--primary); color: var(--primary); background: rgba(6, 182, 212, 0.06); }

/* ── Grid de cards ───────────────────────────────────────────────────── */
.dev-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: var(--space-3); }

.dev-card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); cursor: pointer; display: flex; flex-direction: column; gap: var(--space-3); transition: border-color var(--transition), transform var(--transition); }
.dev-card:hover { border-color: var(--primary); transform: translateY(-1px); }
.dev-card.state-offline { opacity: 0.72; }

/* ── Card top: title + badge ─────────────────────────────────────────── */
.card-top { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-2); }
.card-title-block { min-width: 0; flex: 1; }
.card-title { font-family: var(--font-sans); font-size: var(--text-md); font-weight: 600; color: var(--text); margin: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-sub { font-size: var(--text-xs); color: var(--text-muted); margin: 2px 0 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.state-badge { font-size: 10px; letter-spacing: .5px; padding: 2px 8px; border-radius: 999px; border: 1px solid; flex-shrink: 0; }
.badge-online  { background: rgba(52, 211, 153, .08); color: var(--success);  border-color: rgba(52, 211, 153, .25); }
.badge-warning { background: rgba(251, 191, 36, .08); color: #fbbf24;         border-color: rgba(251, 191, 36, .25); }
.badge-offline { background: rgba(248, 113, 113, .08); color: var(--danger);  border-color: rgba(248, 113, 113, .25); }
.badge-pending { background: rgba(129, 140, 248, .08); color: #818cf8;        border-color: rgba(129, 140, 248, .25); }

/* ── Métricas ────────────────────────────────────────────────────────── */
.metrics { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--space-3); }
.metric { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.metric-label { font-size: 9px; color: var(--text-muted); text-transform: uppercase; letter-spacing: 1.2px; }
.metric-value { font-family: var(--font-mono); font-size: var(--text-md); font-weight: 500; color: var(--text); }
.metric-value.metric-muted { color: var(--text-muted); }
.metric-value.metric-warn { color: #fbbf24; }
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
