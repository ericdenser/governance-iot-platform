<script setup lang="ts">
import { computed } from 'vue'
import { useLiveStateStore } from '@/stores/liveState'

const live = useLiveStateStore()

const label = computed(() =>
  ({ live: 'Ao vivo', connecting: 'Conectando...', disconnected: 'Offline' })[live.connectionStatus]
)
</script>

<template>
  <span class="live-indicator" :class="live.connectionStatus" :title="label">
    <span class="dot" />
    {{ label }}
  </span>
</template>

<style scoped>
.live-indicator {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--text-muted);
  white-space: nowrap;
}
.dot { width: 8px; height: 8px; border-radius: 50%; background: var(--text-muted); }
.live .dot { background: var(--success, #22c55e); animation: pulse 2s infinite; }
.connecting .dot { background: var(--warning, #eab308); }
.disconnected .dot { background: var(--danger, #ef4444); }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: .4; }
}
</style>
