<script setup lang="ts">
import { computed } from 'vue'

// Fracao "x/y ok" + barra segmentada por resultado
const props = defineProps<{
  total: number
  success: number
  failed: number
  pending: number
  skipped?: number
  notFound?: number
}>()

const other = computed(() => (props.skipped ?? 0) + (props.notFound ?? 0))
const base = computed(() => Math.max(props.total, props.success + props.failed + props.pending + other.value, 1))
const pct = (n: number) => `${(n / base.value) * 100}%`
</script>

<template>
  <div class="batch-progress">
    <span class="fraction mono">{{ success }}/{{ total }} ok</span>
    <div class="bar" role="img" :aria-label="`${success} de ${total} concluídos, ${failed} falharam, ${pending} pendentes`">
      <span v-if="success" class="seg ok" :style="{ width: pct(success) }" />
      <span v-if="failed" class="seg fail" :style="{ width: pct(failed) }" />
      <span v-if="pending" class="seg pending" :style="{ width: pct(pending) }" />
      <span v-if="other" class="seg other" :style="{ width: pct(other) }" />
    </div>
  </div>
</template>

<style scoped>
.batch-progress { display: flex; align-items: center; gap: var(--space-2); min-width: 0; }
.fraction { font-size: var(--text-xs); color: var(--text-secondary); flex-shrink: 0; }
.bar { flex: 1; display: flex; height: 4px; gap: 2px; min-width: 40px; }
.seg { height: 100%; border-radius: 1px; }
.ok      { background: var(--success); }
.fail    { background: var(--danger); }
.pending { background: var(--text-muted); }
.other   { background: var(--border-strong); }
</style>
