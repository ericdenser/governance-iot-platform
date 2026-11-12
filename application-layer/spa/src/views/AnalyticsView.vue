<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import { devicesApi } from '@/services/devices'
import { useLiveStateStore } from '@/stores/liveState'
import { relativeTime } from '@/utils/format'
import type { DeviceSummaryDTO } from '@/types/models'


type Metric = 'battery' | 'rssi' | 'tempC'
type SortKey = 'worst' | 'name' | 'value-asc' | 'value-desc' | 'lastSeen'
type Extended = DeviceSummaryDTO & { rssi?: number | null; tempC?: number | null }

const liveStore = useLiveStateStore()

const devices = ref<Extended[]>([])
const loading = ref(true)
const metric = ref<Metric>('battery')
const sort = ref<SortKey>('worst')
const onlyActive = ref(true)
const topN = ref<number>(50)

// Status vem sempre do DB (fonte de verdade — handlers HTTP escrevem la).
// Live state so sobrescreve metricas transientes (bateria/rssi/temp/lastSeen).
const merged = computed<Extended[]>(() =>
  devices.value.map((d) => {
    const lv = liveStore.devices.get(d.deviceId)
    if (!lv) return d
    return {
      ...d,
      batteryMv: lv.batteryMv ?? d.batteryMv,
      batteryTs: lv.batteryTs ?? d.batteryTs,
      rssi: lv.rssi ?? d.rssi,
      rssiTs: lv.rssiTs ?? d.rssiTs,
      tempC: lv.tempC ?? d.tempC,
      tempTs: lv.tempTs ?? d.tempTs,
      lastSeen: lv.lastSeen ?? d.lastSeen,
    }
  }),
)

const METRIC_LABEL: Record<Metric, string> = {
  battery: 'Bateria (%)',
  rssi: 'RSSI (dBm)',
  tempC: 'Temperatura (°C)',
}

const rawValue = (d: Extended, m: Metric): number | null => {
  if (m === 'battery') return d.batteryMv ?? null
  if (m === 'rssi') return d.rssi ?? null
  return d.tempC ?? null
}

const chartValue = (d: Extended, m: Metric): number | null => {
  const raw = rawValue(d, m)
  if (raw == null) return null
  if (m === 'battery') {
    return Math.max(0, Math.min(100, Math.round(((raw - 3000) / (4200 - 3000)) * 100)))
  }
  return raw
}

const metricTs = (d: Extended, m: Metric): string | null | undefined => {
  if (m === 'battery') return d.batteryTs
  if (m === 'rssi') return d.rssiTs
  return d.tempTs
}

// Bateria e temperatura usam os limites do alerta do backend (app.health.*)
type Level = 'good' | 'warn' | 'bad'
const levelOf = (d: Extended, m: Metric): Level | null => {
  const raw = rawValue(d, m)
  if (raw == null) return null
  if (m === 'battery') return raw < 3500 ? 'bad' : raw < 3700 ? 'warn' : 'good'
  if (m === 'rssi') return raw < -85 ? 'bad' : raw < -70 ? 'warn' : 'good'
  return raw > 70 ? 'bad' : raw > 60 ? 'warn' : 'good'
}

const LEVEL_LABEL: Record<Level, string> = { good: 'ok', warn: 'atenção', bad: 'crítico' }
const LEVEL_RANK: Record<Level, number> = { bad: 0, warn: 1, good: 2 }

// Scale of the track and where the warn/bad limits fall on it
const SCALE: Record<Metric, { min: number; max: number; marks: number[]; ends: [string, string] }> = {
  battery: { min: 0,    max: 100, marks: [(3500 - 3000) / 12, (3700 - 3000) / 12], ends: ['0%', '100%'] },
  rssi:    { min: -100, max: -30, marks: [-85, -70],                               ends: ['-100 dBm', '-30 dBm'] },
  tempC:   { min: 0,    max: 80,  marks: [60, 70],                                 ends: ['0°C', '80°C'] },
}
const toPct = (v: number, m: Metric): number => {
  const { min, max } = SCALE[m]
  return Math.max(0, Math.min(100, ((v - min) / (max - min)) * 100))
}

const STALE_MS = 10 * 60 * 1000
const isStale = (d: Extended, m: Metric): boolean => {
  const ts = metricTs(d, m)
  return !ts || Date.now() - new Date(ts).getTime() > STALE_MS
}

const valueText = (d: Extended, m: Metric): string => {
  const raw = rawValue(d, m)
  if (raw == null) return '—'
  if (m === 'battery') return `${chartValue(d, m)}%`
  if (m === 'rssi') return `${Math.round(raw)} dBm`
  return `${raw.toFixed(1)}°C`
}

const scopedDevices = computed<Extended[]>(() => {
  const list = onlyActive.value
    ? merged.value.filter(d => d.status === 'ACTIVE')
    : merged.value.slice()

  const cmp = (a: Extended, b: Extended) => {
    if (sort.value === 'name') return a.name.localeCompare(b.name)
    if (sort.value === 'worst') {
      const la = levelOf(a, metric.value), lb = levelOf(b, metric.value)
      if (la == null || lb == null) return la == null ? (lb == null ? 0 : 1) : -1
      if (la !== lb) return LEVEL_RANK[la] - LEVEL_RANK[lb]
      // inside a level, closest to the bad side first (high is bad only for temperature)
      const va = chartValue(a, metric.value) ?? 0, vb = chartValue(b, metric.value) ?? 0
      return metric.value === 'tempC' ? vb - va : va - vb
    }
    if (sort.value === 'lastSeen') {
      const ta = a.lastSeen ? new Date(a.lastSeen).getTime() : 0
      const tb = b.lastSeen ? new Date(b.lastSeen).getTime() : 0
      return tb - ta
    }
    const va = chartValue(a, metric.value)
    const vb = chartValue(b, metric.value)
    if (va == null && vb == null) return 0
    if (va == null) return 1
    if (vb == null) return -1
    return sort.value === 'value-asc' ? va - vb : vb - va
  }

  list.sort(cmp)
  return topN.value > 0 ? list.slice(0, topN.value) : list
})

const rows = computed(() => {
  const m = metric.value
  return scopedDevices.value.map((d) => {
    const v = chartValue(d, m)
    const level = levelOf(d, m)
    return {
      id: d.deviceId,
      name: d.name,
      has: v != null,
      pct: v == null ? 0 : toPct(v, m),
      level,
      levelLabel: level ? LEVEL_LABEL[level] : 'sem leitura',
      value: valueText(d, m),
      detail: m === 'battery' && d.batteryMv != null ? `${d.batteryMv} mV` : '',
      stale: v != null && isStale(d, m),
      age: v == null ? '' : relativeTime(metricTs(d, m)),
    }
  })
})

const marks = computed(() => SCALE[metric.value].marks.map(v => toPct(v, metric.value)))
const scaleEnds = computed(() => SCALE[metric.value].ends)

const stats = computed(() => {
  const items = scopedDevices.value
  const values = items
    .map(d => chartValue(d, metric.value))
    .filter((v): v is number => v != null)
  const missing = items.length - values.length
  if (!values.length) return { count: items.length, avg: null, median: null, critical: 0, missing }
  const sum = values.reduce((a, b) => a + b, 0)
  const avg = sum / values.length
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  const median = sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
  const critical = items.filter(d => levelOf(d, metric.value) === 'bad').length
  return { count: items.length, avg, median, critical, missing }
})

const fmtStat = (n: number | null): string => {
  if (n == null) return '—'
  if (metric.value === 'battery') return `${Math.round(n)}%`
  if (metric.value === 'rssi') return `${Math.round(n)} dBm`
  return `${n.toFixed(1)}°C`
}

const load = async () => {
  loading.value = true
  try {
    devices.value = (await devicesApi.listAll()) as Extended[]
  } finally { loading.value = false }
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <AppCard title="Métricas por dispositivo">

      <div class="toolbar">
        <label class="ctl">
          <span class="ctl-label">Métrica</span>
          <select v-model="metric" class="field">
            <option value="battery">Bateria</option>
            <option value="rssi">RSSI</option>
            <option value="tempC">Temperatura</option>
          </select>
        </label>

        <label class="ctl">
          <span class="ctl-label">Ordenação</span>
          <select v-model="sort" class="field">
            <option value="worst">Pior primeiro</option>
            <option value="name">Nome (A→Z)</option>
            <option value="value-desc">Valor (maior → menor)</option>
            <option value="value-asc">Valor (menor → maior)</option>
            <option value="lastSeen">Última atualização</option>
          </select>
        </label>

        <label class="ctl">
          <span class="ctl-label">Mostrar</span>
          <select v-model.number="topN" class="field">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
            <option :value="0">Todos</option>
          </select>
        </label>

        <label class="ctl checkbox">
          <input type="checkbox" v-model="onlyActive" />
          <span>Só ativos</span>
        </label>
      </div>

      <AppSkeleton v-if="loading" variant="list" :rows="8" />
      <EmptyState v-else-if="!scopedDevices.length" title="Nenhum dispositivo com esses filtros" hint="Desmarque “Só ativos” ou troque o grupo para ver mais dispositivos." />
      <div v-else class="rank" role="list" :aria-label="METRIC_LABEL[metric]">
        <div class="rank-scale" aria-hidden="true">
          <span class="rank-scale-track">
            <span>{{ scaleEnds[0] }}</span>
            <span>{{ scaleEnds[1] }}</span>
          </span>
        </div>
        <router-link
          v-for="r in rows" :key="r.id" :to="`/devices/${r.id}`"
          class="rank-row" :class="[r.level ?? 'none', { stale: r.stale }]" role="listitem"
        >
          <span class="rank-name" :title="r.name">{{ r.name }}</span>
          <span class="rank-track">
            <span v-for="(mk, i) in marks" :key="i" class="rank-mark" :style="{ left: mk + '%' }" />
            <span v-if="r.has" class="rank-bar" :style="{ width: r.pct + '%' }" />
          </span>
          <span class="rank-value num">{{ r.value }}</span>
          <span class="rank-level">{{ r.levelLabel }}</span>
          <span class="rank-age">{{ r.detail }}<template v-if="r.detail && r.age"> · </template>{{ r.age }}</span>
        </router-link>
      </div>

      <div v-if="!loading && scopedDevices.length" class="stats mono">
        <span><strong>{{ stats.count }}</strong> dispositivo(s)</span>
        <span class="sep">·</span>
        <span>média <strong>{{ fmtStat(stats.avg) }}</strong></span>
        <span class="sep">·</span>
        <span>mediana <strong>{{ fmtStat(stats.median) }}</strong></span>
        <span class="sep">·</span>
        <span class="danger"><strong>{{ stats.critical }}</strong> crítico(s)</span>
        <span class="sep">·</span>
        <span class="muted"><strong>{{ stats.missing }}</strong> sem leitura</span>
      </div>
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: flex-end; gap: var(--space-4); flex-wrap: wrap; margin-bottom: var(--space-4); }
.ctl { display: flex; flex-direction: column; gap: 4px; }
.ctl-label { font-size: var(--text-xs); color: var(--text-muted); }
.ctl.checkbox { flex-direction: row; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); }
.field { width: auto; min-width: 160px; padding: 7px 12px; font-family: var(--font-mono); }

/* Ranked horizontal bars: name | track | value | level | age */
.rank { display: flex; flex-direction: column; }
.rank-row, .rank-scale {
  display: grid;
  grid-template-columns: minmax(120px, 220px) minmax(120px, 1fr) 76px 76px minmax(90px, 150px);
  align-items: center;
  gap: var(--space-3);
}
.rank-scale { padding: 0 var(--space-2) var(--space-1); }
.rank-scale-track { grid-column: 2; display: flex; justify-content: space-between; font-size: var(--text-xs); color: var(--text-muted); font-family: var(--font-mono); }

.rank-row { padding: 7px var(--space-2); border-radius: var(--radius-md); color: var(--text); text-decoration: none; transition: background var(--transition); }
.rank-row:hover { background: var(--panel); color: var(--text); }
.rank-name { font-size: var(--text-sm); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.rank-track { position: relative; height: 10px; background: var(--muted); border-radius: var(--radius-pill); }
.rank-bar { position: absolute; inset: 0 auto 0 0; min-width: 4px; border-radius: var(--radius-pill); background: var(--bar-color); transition: width 250ms ease; }
.rank-mark { position: absolute; top: -3px; bottom: -3px; width: 1px; background: var(--border-strong); z-index: 1; }

.rank-row.good { --bar-color: var(--chart-good); }
.rank-row.warn { --bar-color: var(--chart-warn); }
.rank-row.bad  { --bar-color: var(--chart-bad); }
.rank-row.stale .rank-bar { opacity: 0.4; }

.rank-value { font-family: var(--font-mono); font-size: var(--text-sm); text-align: right; }
.rank-level { font-size: var(--text-xs); color: var(--text-muted); }
.rank-row.warn .rank-level { color: var(--warning); }
.rank-row.bad  .rank-level { color: var(--danger); font-weight: 500; }
.rank-age { font-size: var(--text-xs); color: var(--text-muted); text-align: right; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

@media (max-width: 767px) {
  .rank-row, .rank-scale { grid-template-columns: minmax(90px, 1fr) minmax(80px, 1.2fr) 68px; }
  .rank-level, .rank-age { display: none; }
}
@media (prefers-reduced-motion: reduce) { .rank-bar { transition: none; } }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; font-size: var(--text-sm); }

.stats { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; margin-top: var(--space-4); padding-top: var(--space-3); border-top: 1px solid var(--border); font-size: var(--text-xs); color: var(--text-secondary); }
.stats .sep { color: var(--text-muted); opacity: 0.5; }
.stats .danger { color: var(--danger); }
.stats .muted { color: var(--text-muted); }
.mono { font-family: var(--font-mono); }
</style>
