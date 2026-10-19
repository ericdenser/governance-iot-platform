<script setup lang="ts">
import { computed } from 'vue'
import { statusInfo, type StatusKind } from '@/utils/statusLabels'

// Status em texto colorido + icone. O codigo original fica no tooltip.
const props = defineProps<{
  kind: StatusKind
  code: string | null | undefined
  label?: string
}>()

const info = computed(() => statusInfo(props.kind, props.code))
</script>

<template>
  <span class="status" :class="`tone-${info.tone}`" :title="code ?? undefined">
    <svg class="icon" viewBox="0 0 16 16" aria-hidden="true">
      <polyline v-if="info.icon === 'check'" points="3.5,8.5 6.5,11.5 12.5,4.5" />
      <g v-else-if="info.icon === 'cross'">
        <line x1="4.5" y1="4.5" x2="11.5" y2="11.5" />
        <line x1="11.5" y1="4.5" x2="4.5" y2="11.5" />
      </g>
      <g v-else-if="info.icon === 'half'">
        <circle cx="8" cy="8" r="5.5" />
        <path d="M8 2.5 A5.5 5.5 0 0 1 8 13.5 Z" class="fill" />
      </g>
      <circle v-else-if="info.icon === 'pending'" cx="8" cy="8" r="5" />
      <g v-else-if="info.icon === 'alert'">
        <line x1="8" y1="3.5" x2="8" y2="9" />
        <line x1="8" y1="12" x2="8" y2="12.5" />
      </g>
      <line v-else x1="4.5" y1="8" x2="11.5" y2="8" />
    </svg>
    <span>{{ label ?? info.label }}</span>
  </span>
</template>

<style scoped>
.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: var(--text-sm);
  font-weight: 500;
  white-space: nowrap;
  color: var(--text-secondary);
}
.icon {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.icon .fill { fill: currentColor; stroke: none; }

.tone-success { color: var(--success); }
.tone-warning { color: var(--warning); }
.tone-danger  { color: var(--danger); }
.tone-accent  { color: var(--accent); }
.tone-neutral { color: var(--text-secondary); }
</style>
