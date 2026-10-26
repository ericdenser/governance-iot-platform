<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { Line } from 'vue-chartjs'
import {
  Chart as ChartJS,
  LineElement,
  PointElement,
  LinearScale,
  Tooltip,
  Legend,
  type TooltipItem,
} from 'chart.js'
import AppCard from '@/components/AppCard.vue'
import { telemetryApi } from '@/services/telemetry'
import { cssVar } from '@/utils/cssVar'
import type { DeviceSummaryDTO, TelemetryPointDTO } from '@/types/models'

ChartJS.register(LineElement, PointElement, LinearScale, Tooltip, Legend)

const props = defineProps<{ devices: DeviceSummaryDTO[] }>()

const MAX_DEVICES = 5
const REFRESH_MS = 60_000
const STORAGE_KEY = 'dashboard_chart_config'

const WINDOWS = {
  '1h':  { label: '1 h',  minutes: 60,   resolution: '1m' },
  '6h':  { label: '6 h',  minutes: 360,  resolution: '5m' },
  '24h': { label: '24 h', minutes: 1440, resolution: '15m' },
} as const
type WindowKey = keyof typeof WINDOWS

// So saude do device. Leitura de sensor fica no Grafana
const METRICS: Record<string, string> = {
  temp_c:     'Temperatura do chip (°C)',
  rssi:       'RSSI (dBm)',
  battery_mv: 'Bateria (mV)',
}

interface ChartConfig {
  metric: string
  window: WindowKey
  deviceIds: string[]
}

const loadConfig = (): ChartConfig | null => {
  try {
    const c = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')
    if (c) {
      return {
        metric: c.metric in METRICS ? c.metric : 'rssi',
        window: c.window in WINDOWS ? c.window : '1h',
        deviceIds: Array.isArray(c.deviceIds) ? c.deviceIds.slice(0, MAX_DEVICES) : [],
      }
    }
  } catch { /* storage indisponivel ou config corrompida: usa default */ }
  return null
}

const saved = loadConfig()
const config = ref<ChartConfig>(saved ?? { metric: 'rssi', window: '1h', deviceIds: [] })
const showConfig = ref(false)
const series = ref(new Map<string, TelemetryPointDTO[]>())
const loading = ref(false)
const failed = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

watch(config, (c) => {
  try { localStorage.setItem(STORAGE_KEY, JSON.stringify(c)) } catch { /* ignora */ }
}, { deep: true })

const selectable = computed(() =>
  props.devices.filter(d => d.status !== 'REVOKED' && d.status !== 'PENDING'),
)

// Sem config salva: comeca com os primeiros devices ativos (uma vez so)
let seeded = !!saved
watch(selectable, (list) => {
  if (seeded || !list.length) return
  seeded = true
  config.value.deviceIds = list.filter(d => d.status === 'ACTIVE').slice(0, 3).map(d => d.deviceId)
}, { immediate: true })

const selectedDevices = computed(() =>
  config.value.deviceIds
    .map(id => props.devices.find(d => d.deviceId === id))
    .filter((d): d is DeviceSummaryDTO => !!d),
)

const toggleDevice = (id: string) => {
  const ids = config.value.deviceIds
  const i = ids.indexOf(id)
  if (i >= 0) ids.splice(i, 1)
  else if (ids.length < MAX_DEVICES) ids.push(id)
}

const colorOf = (id: string): string => {
  const i = config.value.deviceIds.indexOf(id)
  return i >= 0 ? cssVar(`--chart-series-${i + 1}`) : cssVar('--chart-empty')
}

const fetchSeries = async () => {
  const ids = config.value.deviceIds
  if (!ids.length) { series.value = new Map(); return }
  const w = WINDOWS[config.value.window]
  loading.value = !series.value.size
  const results = await Promise.allSettled(ids.map(id => telemetryApi.series(id, w.minutes, w.resolution)))
  const next = new Map<string, TelemetryPointDTO[]>()
  results.forEach((r, i) => { if (r.status === 'fulfilled') next.set(ids[i], r.value.data ?? []) })
  failed.value = results.every(r => r.status === 'rejected')
  series.value = next
  loading.value = false
}

watch(() => [config.value.window, config.value.deviceIds.join(',')], fetchSeries)

onMounted(() => {
  fetchSeries()
  timer = setInterval(fetchSeries, REFRESH_MS)
})
onUnmounted(() => { if (timer) clearInterval(timer) })

const metricLabel = (key: string) => METRICS[key] ?? key


const datasets = computed(() =>
  selectedDevices.value.map((d) => {
    const color = colorOf(d.deviceId)
    const data = (series.value.get(d.deviceId) ?? [])
      .filter(p => p.readings[config.value.metric] != null)
      .map(p => ({ x: new Date(p.timestamp).getTime(), y: p.readings[config.value.metric] }))
    return {
      label: d.name,
      data,
      borderColor: color,
      backgroundColor: color,
      borderWidth: 2,
      pointRadius: 0,
      pointHoverRadius: 3,
      tension: 0.25,
    }
  }),
)

const hasData = computed(() => datasets.value.some(ds => ds.data.length))

const fmtTime = (ms: number) =>
  new Date(ms).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })

const chartOptions = computed(() => {
  const now = Date.now()
  const w = WINDOWS[config.value.window]
  return {
    responsive: true,
    maintainAspectRatio: false,
    animation: false as const,
    interaction: { mode: 'nearest' as const, axis: 'x' as const, intersect: false },
    scales: {
      x: {
        type: 'linear' as const,
        min: now - w.minutes * 60_000,
        max: now,
        ticks: { color: cssVar('--text-muted'), font: { size: 11 }, maxTicksLimit: 8, callback: (v: string | number) => fmtTime(Number(v)) },
        grid: { display: false },
      },
      y: {
        ticks: { color: cssVar('--text-muted'), font: { size: 11 } },
        grid: { color: cssVar('--border') },
        title: { display: true, text: metricLabel(config.value.metric), color: cssVar('--text-muted'), font: { size: 11 } },
      },
    },
    plugins: {
      legend: { display: false },
      tooltip: {
        callbacks: {
          title: (items: TooltipItem<'line'>[]) =>
            items[0]?.parsed.x != null ? new Date(items[0].parsed.x).toLocaleString('pt-BR') : '',
          label: (ctx: TooltipItem<'line'>) =>
            `${ctx.dataset.label}: ${ctx.parsed.y != null ? Number(ctx.parsed.y.toFixed(2)) : '—'}`,
        },
      },
    },
  }
})
</script>

<template>
  <AppCard title="Histórico de métricas" class="panel">
    <template #actions>
      <div class="actions">
        <select v-model="config.metric" class="field" aria-label="Métrica">
          <option v-for="(label, key) in METRICS" :key="key" :value="key">{{ label }}</option>
        </select>
        <select v-model="config.window" class="field" aria-label="Janela">
          <option v-for="(w, key) in WINDOWS" :key="key" :value="key">{{ w.label }}</option>
        </select>
        <button class="icon-btn" :class="{ active: showConfig }" title="Escolher dispositivos" @click="showConfig = !showConfig">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="4" y1="6" x2="20" y2="6"/><line x1="7" y1="12" x2="17" y2="12"/><line x1="10" y1="18" x2="14" y2="18"/>
          </svg>
        </button>
      </div>
    </template>

    <div v-if="showConfig" class="config">
      <span class="config-hint">Até {{ MAX_DEVICES }} dispositivos</span>
      <div class="chips">
        <button
          v-for="d in selectable"
          :key="d.deviceId"
          class="chip"
          :class="{ active: config.deviceIds.includes(d.deviceId) }"
          :disabled="!config.deviceIds.includes(d.deviceId) && config.deviceIds.length >= MAX_DEVICES"
          @click="toggleDevice(d.deviceId)"
        >
          <span class="chip-dot" :style="{ background: colorOf(d.deviceId) }" />
          {{ d.name }}
        </button>
        <span v-if="!selectable.length" class="empty-inline">Nenhum dispositivo ativo</span>
      </div>
    </div>

    <div v-if="selectedDevices.length" class="legend">
      <span v-for="d in selectedDevices" :key="d.deviceId" class="legend-item">
        <span class="legend-line" :style="{ background: colorOf(d.deviceId) }" />{{ d.name }}
      </span>
    </div>

    <div class="chart-wrap">
      <div v-if="!selectedDevices.length" class="empty">Escolha dispositivos no filtro acima</div>
      <div v-else-if="loading" class="empty">Carregando...</div>
      <div v-else-if="failed" class="empty">Histórico indisponível</div>
      <div v-else-if="!hasData" class="empty">Sem leituras de {{ metricLabel(config.metric) }} nessa janela</div>
      <Line v-else :data="{ datasets }" :options="chartOptions" />
    </div>
  </AppCard>
</template>

<style scoped>
.actions { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }

.field {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 4px 8px;
  font-size: var(--text-xs);
  color: var(--text);
  font-family: var(--font-sans);
  outline: none;
  max-width: 200px;
}
.field:focus { border-color: var(--primary); }

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
.icon-btn:hover, .icon-btn.active { background: var(--panel); color: var(--text); }

.config {
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  padding-bottom: var(--space-3);
  margin-bottom: var(--space-3);
  border-bottom: 1px solid var(--border);
}
.config-hint { font-size: var(--text-xs); color: var(--text-muted); }

.chips { display: flex; gap: var(--space-2); flex-wrap: wrap; max-height: 120px; overflow-y: auto; }
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
.chip:hover:not(:disabled) { color: var(--text); background: var(--panel); }
.chip.active { color: var(--text); border-color: var(--border-strong); background: var(--panel); }
.chip:disabled { opacity: 0.4; cursor: not-allowed; }
.chip-dot { width: 7px; height: 7px; border-radius: 50%; }

.legend { display: flex; gap: var(--space-4); flex-wrap: wrap; margin-bottom: var(--space-3); font-size: var(--text-xs); color: var(--text-secondary); }
.legend-item { display: inline-flex; align-items: center; gap: 6px; }
.legend-line { width: 14px; height: 2px; border-radius: 1px; }

.chart-wrap { height: 280px; position: relative; }
.empty { height: 100%; display: flex; align-items: center; justify-content: center; color: var(--text-muted); font-size: var(--text-sm); }
.empty-inline { font-size: var(--text-xs); color: var(--text-muted); }
</style>
