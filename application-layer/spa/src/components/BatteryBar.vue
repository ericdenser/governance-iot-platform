<script setup lang="ts">
import { computed } from 'vue'

interface Props {
  mv?: number | null
  ts?: string | Date | null
  deviceStatus?: string | null
  size?: 'sm' | 'lg'
}
const props = withDefaults(defineProps<Props>(), {
  mv: null,
  ts: null,
  deviceStatus: null,
  size: 'sm',
})

const TOTAL_BINS = 6
const STALE_MS = 10 * 60 * 1000
const CRITICAL_THRESHOLD_MV = 3200

// bins = floor((mv - 3000) / 200), clamp [0, 6]
// 4200=6, 4000=5, 3800=4, 3600=3, 3400=2, 3200=1, <3200=0
const bins = computed(() => {
  if (props.mv == null) return 0
  const raw = Math.floor((props.mv - 3000) / 200)
  return Math.max(0, Math.min(TOTAL_BINS, raw))
})

const isHibernating = computed(() => props.deviceStatus === 'CRITICAL_BATTERY')

// Bateria < 3200 sem CRITICAL_BATTERY = anomalia (hibernação off no Kconfig,
// firmware antigo, ou crash antes de reportar). Mostra 1 vermelho piscando.
const isCriticalActive = computed(
  () => !isHibernating.value && props.mv != null && props.mv < CRITICAL_THRESHOLD_MV,
)

const filledColor = computed<'green' | 'yellow' | 'red'>(() => {
  if (bins.value >= 5) return 'green'
  if (bins.value >= 3) return 'yellow'
  return 'red'
})

const stale = computed(() => {
  if (!props.ts) return false
  const ms = new Date(props.ts).getTime()
  if (!Number.isFinite(ms)) return false
  return Date.now() - ms > STALE_MS
})

const relativeTime = (ts: string | Date): string => {
  const diff = Date.now() - new Date(ts).getTime()
  if (!Number.isFinite(diff) || diff < 0) return '—'
  const min = Math.floor(diff / 60000)
  if (min < 1) return 'agora'
  if (min < 60) return `há ${min} min`
  const h = Math.floor(min / 60)
  if (h < 24) return `há ${h}h`
  return `há ${Math.floor(h / 24)}d`
}

const tooltip = computed(() => {
  if (isHibernating.value) return 'Hibernando por bateria baixa (CRITICAL_BATTERY)'
  if (props.mv == null) return 'Sem leitura de bateria'
  const parts = [`${Math.round(props.mv)} mV`]
  if (props.ts) parts.push(relativeTime(props.ts))
  if (isCriticalActive.value) parts.push('bateria crítica')
  if (stale.value) parts.push('leitura desatualizada')
  return parts.join(' · ')
})
</script>

<template>
  <span v-if="mv == null && !isHibernating" class="battery-empty" :title="tooltip">—</span>
  <span
    v-else
    class="battery-bar"
    :class="[
      `battery-bar--${size}`,
      { 'battery-bar--stale': stale, 'battery-bar--hibernating': isHibernating },
    ]"
    :title="tooltip"
    role="img"
    :aria-label="tooltip"
  >
    <span
      v-for="i in TOTAL_BINS"
      :key="i"
      class="battery-seg"
      :class="{
        'battery-seg--filled': !isHibernating && i <= bins,
        'battery-seg--green': !isHibernating && i <= bins && filledColor === 'green',
        'battery-seg--yellow': !isHibernating && i <= bins && filledColor === 'yellow',
        'battery-seg--red': !isHibernating && i <= bins && filledColor === 'red',
        'battery-seg--critical': isCriticalActive && i === 1,
        'battery-seg--hibernating': isHibernating,
      }"
    />
  </span>
</template>

<style scoped>
.battery-empty { color: var(--text-muted); }

.battery-bar {
  display: inline-flex;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 3px;
  vertical-align: middle;
  background: var(--panel);
}
.battery-bar--sm { height: 14px; width: 52px; }
.battery-bar--lg { height: 22px; width: 96px; gap: 3px; padding: 3px; border-radius: 4px; }

.battery-bar--stale { opacity: 0.5; }

.battery-seg {
  flex: 1;
  background: var(--border);
  border-radius: 1px;
  transition: background var(--transition);
}

.battery-seg--filled.battery-seg--green  { background: #22c55e; }
.battery-seg--filled.battery-seg--yellow { background: #eab308; }
.battery-seg--filled.battery-seg--red    { background: #ef4444; }

.battery-seg--critical {
  background: #ef4444;
  animation: battery-blink 1.2s ease-in-out infinite;
}

.battery-seg--hibernating {
  background: #6b7280;
}
.battery-bar--hibernating {
  border-color: #6b7280;
}

@keyframes battery-blink {
  0%, 100% { opacity: 1; }
  50%      { opacity: 0.35; }
}
</style>
