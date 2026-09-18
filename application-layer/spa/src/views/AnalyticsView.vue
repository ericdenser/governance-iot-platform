<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import LiveIndicator from '@/components/LiveIndicator.vue'
import { Bar } from 'vue-chartjs'
import {
  Chart as ChartJS,
  Title,
  Tooltip,
  Legend,
  BarElement,
  CategoryScale,
  LinearScale,
} from 'chart.js'
import { devicesApi } from '@/services/devices'
import { useLiveStateStore } from '@/stores/liveState'
import type { DeviceSummaryDTO } from '@/types/models'

ChartJS.register(Title, Tooltip, Legend, BarElement, CategoryScale, LinearScale)

type Metric = 'battery' | 'rssi' | 'tempC'
type SortKey = 'name' | 'value-asc' | 'value-desc' | 'lastSeen'
type Extended = DeviceSummaryDTO & { rssi?: number | null; tempC?: number | null }

const router = useRouter()
const liveStore = useLiveStateStore()

const devices = ref<Extended[]>([])
const loading = ref(true)
const metric = ref<Metric>('battery')
const sort = ref<SortKey>('name')
const onlyActive = ref(true)
const topN = ref<number>(50)

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

const colorFor = (m: Metric, v: number | null): string => {
  if (v == null) return 'rgba(148, 163, 184, 0.35)'
  if (m === 'battery') {
    if (v < 25) return 'rgba(239, 68, 68, 0.85)'
    if (v < 50) return 'rgba(251, 191, 36, 0.85)'
    return 'rgba(34, 197, 94, 0.85)'
  }
  if (m === 'rssi') {
    if (v < -85) return 'rgba(239, 68, 68, 0.85)'
    if (v < -70) return 'rgba(251, 191, 36, 0.85)'
    return 'rgba(34, 197, 94, 0.85)'
  }
  if (v > 55) return 'rgba(239, 68, 68, 0.85)'
  if (v > 45) return 'rgba(251, 191, 36, 0.85)'
  return 'rgba(34, 197, 94, 0.85)'
}

const scopedDevices = computed<Extended[]>(() => {
  const list = onlyActive.value
    ? merged.value.filter(d => d.status === 'ACTIVE')
    : merged.value.slice()

  const cmp = (a: Extended, b: Extended) => {
    if (sort.value === 'name') return a.name.localeCompare(b.name)
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

const chartData = computed(() => {
  const items = scopedDevices.value
  return {
    labels: items.map(d => d.name),
    datasets: [{
      label: METRIC_LABEL[metric.value],
      data: items.map(d => chartValue(d, metric.value) ?? 0),
      backgroundColor: items.map(d => colorFor(metric.value, chartValue(d, metric.value))),
      borderColor: items.map(d => colorFor(metric.value, chartValue(d, metric.value)).replace('0.85', '1')),
      borderWidth: 1,
      borderRadius: 3,
    }],
  }
})

const chartOptions = computed(() => {
  const m = metric.value
  const items = scopedDevices.value
  const yMin = m === 'rssi' ? -100 : m === 'tempC' ? 0 : 0
  const yMax = m === 'rssi' ? -30 : m === 'tempC' ? 80 : 100
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: { duration: 250 },
    onClick: (_evt: unknown, elements: { index: number }[]) => {
      if (!elements.length) return
      const d = items[elements[0].index]
      if (d) router.push(`/devices/${d.deviceId}`)
    },
    scales: {
      x: {
        ticks: { color: '#94a3b8', maxRotation: 60, minRotation: 45, autoSkip: false, font: { size: 10 } },
        grid: { display: false },
      },
      y: {
        min: yMin,
        max: yMax,
        ticks: { color: '#94a3b8', font: { size: 11 } },
        grid: { color: 'rgba(148, 163, 184, 0.12)' },
        title: { display: true, text: METRIC_LABEL[m], color: '#94a3b8', font: { size: 11 } },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: {
        callbacks: {
          label: (ctx: { dataIndex: number }) => {
            const d = items[ctx.dataIndex]
            const raw = rawValue(d, m)
            const shown = chartValue(d, m)
            const ts = metricTs(d, m)
            const tsFmt = ts ? new Date(ts).toLocaleString('pt-BR') : '—'
            const lines: string[] = []
            if (raw == null) {
              lines.push('sem leitura')
            } else if (m === 'battery') {
              lines.push(`${shown}% (${raw} mV)`)
            } else if (m === 'rssi') {
              lines.push(`${raw} dBm`)
            } else {
              lines.push(`${raw}°C`)
            }
            lines.push(`status: ${d.status}`)
            lines.push(`ts: ${tsFmt}`)
            return lines
          },
          title: (ctx: { label: string }[]) => ctx[0]?.label ?? '',
        },
      },
    },
  }
})

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
  const critical = items.filter(d => {
    const v = chartValue(d, metric.value)
    if (v == null) return false
    if (metric.value === 'battery') return v < 25
    if (metric.value === 'rssi') return v < -85
    return v > 55
  }).length
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
    <AppCard title="Analytics" description="Métrica por device (snapshot atual — atualiza via SSE)">
      <template #actions>
        <LiveIndicator />
      </template>

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
            <option value="name">Nome (A→Z)</option>
            <option value="value-desc">Valor (maior → menor)</option>
            <option value="value-asc">Valor (menor → maior)</option>
            <option value="lastSeen">Última atualização</option>
          </select>
        </label>

        <label class="ctl">
          <span class="ctl-label">Top N</span>
          <select v-model.number="topN" class="field">
            <option :value="20">20</option>
            <option :value="50">50</option>
            <option :value="100">100</option>
            <option :value="0">Todos</option>
          </select>
        </label>

        <label class="ctl checkbox">
          <input type="checkbox" v-model="onlyActive" />
          <span>Só ACTIVE</span>
        </label>
      </div>

      <div v-if="loading" class="empty">Carregando...</div>
      <div v-else-if="!scopedDevices.length" class="empty">Nenhum device no escopo</div>
      <div v-else class="chart-wrap">
        <Bar :data="chartData" :options="chartOptions" />
      </div>

      <div v-if="!loading && scopedDevices.length" class="stats mono">
        <span><strong>{{ stats.count }}</strong> device(s)</span>
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
.ctl-label { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); letter-spacing: .5px; text-transform: uppercase; }
.ctl.checkbox { flex-direction: row; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); }
.ctl.checkbox input { accent-color: var(--primary); width: 14px; height: 14px; }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 7px 12px; font-size: var(--text-sm); color: var(--text); outline: none; font-family: var(--font-mono); min-width: 160px; }
.field:focus { border-color: var(--primary); }

.chart-wrap { height: 420px; }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; font-size: var(--text-sm); }

.stats { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; margin-top: var(--space-4); padding-top: var(--space-3); border-top: 1px solid var(--border); font-size: var(--text-xs); color: var(--text-secondary); }
.stats .sep { color: var(--text-muted); opacity: 0.5; }
.stats .danger { color: var(--danger); }
.stats .muted { color: var(--text-muted); }
.mono { font-family: var(--font-mono); }
</style>
