<script setup lang="ts">
defineProps<{
  label: string
  value: string | number
  variant?: 'default' | 'success' | 'warning' | 'danger' | 'info'
  sub?: string
}>()
</script>

<template>
  <div class="stat-card" :class="variant || 'default'">
    <span class="stat-label">{{ label }}</span>
    <span class="stat-value">{{ value }}</span>
    <span v-if="sub" class="stat-sub">{{ sub }}</span>
  </div>
</template>

<style scoped>
.stat-card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: var(--space-5);
  display: flex;
  flex-direction: column;
  gap: var(--space-1);
}

.stat-label {
  font-size: var(--text-sm);
  color: var(--text-muted);
  font-family: var(--font-sans);
}

.stat-value {
  font-family: var(--font-sans);
  font-size: var(--text-3xl);
  font-weight: 700;
  line-height: 1;
  color: var(--text);
}

.stat-sub {
  font-size: var(--text-xs);
  color: var(--text-muted);
  margin-top: var(--space-1);
}

/* Variants */
.success .stat-value { color: var(--success); }
.success { border-left: 3px solid var(--success); }

.warning .stat-value { color: var(--warning); }
.warning { border-left: 3px solid var(--warning); }

.danger .stat-value { color: var(--danger); }
.danger { border-left: 3px solid var(--danger); }

.info .stat-value { color: var(--info); }
.info { border-left: 3px solid var(--info); }
</style>
