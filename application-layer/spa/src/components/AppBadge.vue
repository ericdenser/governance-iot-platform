<script setup lang="ts">
defineProps<{
  variant?: 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'
  dot?: boolean
}>()
</script>

<template>
  <span class="badge" :class="variant || 'muted'">
    <span v-if="dot" class="badge-dot" />
    <slot />
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 2px 8px;
  border-radius: var(--radius-pill);
  font-family: var(--font-sans);
  font-size: var(--text-xs);
  font-weight: 600;
  letter-spacing: 0.3px;
  white-space: nowrap;
}

.badge-dot {
  width: 6px;
  height: 6px;
  border-radius: var(--radius-pill);
  background: currentColor;
  flex-shrink: 0;
}

.success { color: var(--success); background: var(--success-dim); }
.warning { color: var(--warning); background: var(--warning-dim); }
.danger  { color: var(--danger);  background: var(--danger-dim);  }
.info    { color: var(--info);    background: var(--info-dim);    }
.primary { color: var(--primary); background: var(--primary-dim); }
.muted   { color: var(--text-muted); background: var(--panel);   }
</style>
