<script setup lang="ts">
defineProps<{
  variant?: 'primary' | 'secondary' | 'danger' | 'ghost'
  size?: 'sm' | 'md' | 'lg'
  loading?: boolean
  disabled?: boolean
  type?: 'button' | 'submit' | 'reset'
}>()
</script>

<template>
  <button
    class="btn"
    :class="[variant || 'primary', size || 'md']"
    :disabled="disabled || loading"
    :type="type || 'button'"
  >
    <span v-if="loading" class="btn-spinner" />
    <slot />
  </button>
</template>

<style scoped>
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-2);
  font-family: var(--font-sans);
  font-weight: 500;
  border-radius: var(--radius-md);
  border: 1px solid transparent;
  cursor: pointer;
  transition: all var(--transition);
  white-space: nowrap;
  line-height: 1;
}

.btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

/* Sizes */
.sm { padding: 6px 12px; font-size: var(--text-xs); }
.md { padding: 8px 16px; font-size: var(--text-sm); }
.lg { padding: 11px 22px; font-size: var(--text-base); }

/* Variants */
.primary {
  background: var(--primary);
  color: var(--text-inverse);
  border-color: var(--primary);
}
.primary:hover:not(:disabled) {
  background: var(--primary-hover);
  border-color: var(--primary-hover);
}

.secondary {
  background: var(--panel);
  color: var(--text);
  border-color: var(--border-strong);
}
.secondary:hover:not(:disabled) {
  background: var(--muted);
  border-color: var(--text-muted);
}

.danger {
  background: var(--danger-dim);
  color: var(--danger);
  border-color: var(--danger);
}
.danger:hover:not(:disabled) {
  background: var(--danger);
  color: #fff;
}

.ghost {
  background: transparent;
  color: var(--text-secondary);
  border-color: transparent;
}
.ghost:hover:not(:disabled) {
  background: var(--panel);
  color: var(--text);
}

/* Spinner */
.btn-spinner {
  width: 12px;
  height: 12px;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: var(--radius-pill);
  animation: spin 0.6s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
