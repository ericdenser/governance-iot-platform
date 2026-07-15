<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import L from 'leaflet'
import 'leaflet.markercluster'
import 'leaflet/dist/leaflet.css'
import 'leaflet.markercluster/dist/MarkerCluster.css'
import 'leaflet.markercluster/dist/MarkerCluster.Default.css'
import { devicesApi } from '@/services/devices'
import { useLiveStateStore } from '@/stores/liveState'
import type { DeviceMapPositionDTO } from '@/types/models'

const props = withDefaults(defineProps<{
  statusFilter?: string[]
  autoFit?: boolean
  scrollWheelZoom?: boolean
}>(), {
  statusFilter: () => [],
  autoFit: true,
  scrollWheelZoom: true,
})

const CLUSTER_THRESHOLD = 200

const STATUS_COLORS: Record<string, string> = {
  ACTIVE: '#22c55e',
  PENDING: '#eab308',
  PROVISIONING: '#3b82f6',
  COMMAND_PENDING: '#3b82f6',
  REVOKED: '#ef4444',
  ERROR: '#ef4444',
}
const statusColor = (s: string | null | undefined) => STATUS_COLORS[s ?? ''] ?? '#6b7280'

const router = useRouter()
const liveStore = useLiveStateStore()

const mapEl = ref<HTMLDivElement | null>(null)
const baseline = ref<DeviceMapPositionDTO[]>([])

// Baseline REST + overlay do SSE (live vence). Live pode trazer devices
// que nem estavam no baseline (acabaram de reportar coordenada).
const merged = computed<DeviceMapPositionDTO[]>(() => {
  const byId = new Map<string, DeviceMapPositionDTO>()
  for (const p of baseline.value) byId.set(p.deviceId, p)
  for (const [id, lv] of liveStore.devices) {
    if (lv.lat == null || lv.lon == null) continue
    const base = byId.get(id)
    byId.set(id, {
      deviceId: id,
      name: base?.name ?? null, // SSE não carrega nome; vem do baseline REST
      latitude: lv.lat,
      longitude: lv.lon,
      lastSeen: lv.lastSeen ?? base?.lastSeen ?? null,
      status: lv.status ?? base?.status ?? null,
    })
  }
  return [...byId.values()]
})

const filtered = computed(() => {
  if (!props.statusFilter.length) return merged.value
  const set = new Set(props.statusFilter)
  return merged.value.filter((p) => set.has(p.status ?? ''))
})

// ── Leaflet 
let map: L.Map | null = null
let markerLayer: L.LayerGroup | null = null
let clustered = false
const markers = new Map<string, L.CircleMarker>()
const positionsById = new Map<string, DeviceMapPositionDTO>()
let lastIdsKey = ''
let resizeObserver: ResizeObserver | null = null

const esc = (v: unknown) => String(v ?? '—').replace(/[&<>"']/g, (c) =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]!)

const fmtSeen = (iso: string | null) => (iso ? new Date(iso).toLocaleString('pt-BR') : '—')

const popupContent = (deviceId: string): HTMLElement => {
  const p = positionsById.get(deviceId)
  const el = document.createElement('div')
  el.className = 'device-map-popup'
  if (!p) return el
  el.innerHTML =
    `<div class="dmp-title">${esc(p.name ?? p.deviceId)}</div>` +
    `<div class="dmp-row dmp-mono">${esc(p.deviceId)}</div>` +
    `<div class="dmp-row"><span class="dmp-dot" style="background:${statusColor(p.status)}"></span>${esc(p.status)}</div>` +
    `<div class="dmp-row dmp-mono">${p.latitude.toFixed(6)}, ${p.longitude.toFixed(6)}</div>` +
    `<div class="dmp-row">Visto: ${esc(fmtSeen(p.lastSeen))}</div>` +
    `<a class="dmp-link" href="/devices/${encodeURIComponent(p.deviceId)}">Ver dispositivo →</a>`
  el.querySelector('a')?.addEventListener('click', (e) => {
    e.preventDefault()
    router.push(`/devices/${p.deviceId}`)
  })
  return el
}

const markerStyle = (status: string | null): L.CircleMarkerOptions => ({
  radius: 8,
  color: statusColor(status),
  weight: 2,
  fillColor: statusColor(status),
  fillOpacity: 0.5,
})

const ensureLayerMode = (wantCluster: boolean) => {
  if (!map) return
  if (markerLayer && clustered === wantCluster) return
  const existing = [...markers.values()]
  if (markerLayer) map.removeLayer(markerLayer)
  markerLayer = wantCluster ? L.markerClusterGroup() : L.layerGroup()
  clustered = wantCluster
  existing.forEach((m) => markerLayer!.addLayer(m))
  map.addLayer(markerLayer)
}

const syncMarkers = () => {
  if (!map) return
  const positions = filtered.value

  positionsById.clear()
  for (const p of positions) positionsById.set(p.deviceId, p)

  ensureLayerMode(positions.length > CLUSTER_THRESHOLD)

  // atualiza in-place, adiciona novos, remove ausentes
  for (const p of positions) {
    const existing = markers.get(p.deviceId)
    if (existing) {
      existing.setLatLng([p.latitude, p.longitude])
      existing.setStyle(markerStyle(p.status))
    } else {
      const m = L.circleMarker([p.latitude, p.longitude], markerStyle(p.status))
      m.bindPopup(() => popupContent(p.deviceId))
      markers.set(p.deviceId, m)
      markerLayer!.addLayer(m)
    }
  }
  for (const [id, m] of markers) {
    if (!positionsById.has(id)) {
      markerLayer!.removeLayer(m)
      markers.delete(id)
    }
  }

  // fitBounds só quando o conjunto de devices muda (não a cada movimento)
  const idsKey = positions.map((p) => p.deviceId).sort().join(',')
  if (props.autoFit && idsKey !== lastIdsKey && positions.length) {
    map.fitBounds(L.latLngBounds(positions.map((p) => [p.latitude, p.longitude])), {
      padding: [40, 40],
      maxZoom: 15,
    })
  }
  lastIdsKey = idsKey
}

// Throttle 500ms (leading + trailing)
let throttleTimer: number | null = null
let pendingSync = false
const scheduleSync = () => {
  if (throttleTimer != null) {
    pendingSync = true
    return
  }
  syncMarkers()
  throttleTimer = window.setTimeout(() => {
    throttleTimer = null
    if (pendingSync) {
      pendingSync = false
      scheduleSync()
    }
  }, 500)
}

const fetchBaseline = async () => {
  try {
    const r = await devicesApi.mapPositions()
    baseline.value = r.data
  } catch {
    // mapa segue só com dados do SSE
  }
}

watch(filtered, scheduleSync, { deep: true })
watch(() => props.autoFit, () => {
  lastIdsKey = ''
  scheduleSync()
})
// Reconectou: refetch do baseline pra cobrir eventos perdidos offline
watch(() => liveStore.connectionStatus, (s) => {
  if (s === 'live') fetchBaseline()
})

onMounted(() => {
  map = L.map(mapEl.value!, {
    center: [-14.235, -51.925], // Brasil
    zoom: 4,
    scrollWheelZoom: props.scrollWheelZoom,
  })
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    referrerPolicy: 'origin',
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map)
  markerLayer = L.layerGroup().addTo(map)

  resizeObserver = new ResizeObserver(() => map?.invalidateSize())
  resizeObserver.observe(mapEl.value!)

  fetchBaseline()
  syncMarkers()
})

onUnmounted(() => {
  if (throttleTimer != null) window.clearTimeout(throttleTimer)
  resizeObserver?.disconnect()
  map?.remove()
  map = null
  markers.clear()
})
</script>

<template>
  <div ref="mapEl" class="device-map" />
</template>

<style scoped>
.device-map {
  width: 100%;
  height: 100%;
  min-height: 200px;
  border-radius: var(--radius-md);
  overflow: hidden;
  z-index: 0;
}
</style>

<style>

.leaflet-popup-content-wrapper {
  background: var(--surface);
  color: var(--text);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.leaflet-popup-tip {
  background: var(--surface);
  border: 1px solid var(--border);
}
.device-map-popup {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-family: var(--font-sans);
  font-size: var(--text-sm);
  min-width: 180px;
}
.device-map-popup .dmp-title {
  font-family: var(--font-mono);
  font-weight: 700;
}
.device-map-popup .dmp-row {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--text-muted);
  font-size: var(--text-xs);
}
.device-map-popup .dmp-mono {
  font-family: var(--font-mono);
}
.device-map-popup .dmp-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.device-map-popup .dmp-link {
  margin-top: 4px;
  color: var(--primary);
  font-size: var(--text-xs);
  text-decoration: none;
}
.device-map-popup .dmp-link:hover {
  text-decoration: underline;
}
</style>
