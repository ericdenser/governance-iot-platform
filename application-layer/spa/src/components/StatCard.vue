<script setup lang="ts">
// A cor do variant so aparece com highlight=true (ex: erros > 0)
defineProps<{
  label: string
  value: string | number
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'info'
  highlight?: boolean
  sub?: string
}>()
</script>

<template>
  <div class="stat-card" :class="highlight ? variant : undefined">
    <span class="stat-label">{{ label }}</span>
    <span class="stat-value num">{{ value }}</span>
    <span v-if="sub" class="stat-sub">{{ sub }}</span>
  </div>
</template>

<style scoped>
.stat-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-4) var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.stat-label {
  font-size: var(--text-sm);
  color: var(--text-secondary);
}

.stat-value {
  font-size: var(--text-2xl);
  font-weight: 600;
  line-height: 1.2;
  color: var(--text);
}

.stat-sub {
  font-size: var(--text-xs);
  color: var(--text-muted);
}

.success .stat-value { color: var(--success); }
.warning .stat-value { color: var(--warning); }
.danger  .stat-value { color: var(--danger); }
</style>
