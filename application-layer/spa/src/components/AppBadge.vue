<script setup lang="ts">
// Etiqueta neutra pra marcar atributos (ex: "Provisioning", "Admin").
// Pra status (sucesso/falha/pendente) use o StatusLabel.
defineProps<{
  variant?: 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'
  dot?: boolean
}>()
</script>

<template>
  <span class="badge" :class="variant || 'muted'">
    <slot />
  </span>
</template>

<style scoped>
.badge {
  display: inline-flex;
  align-items: center;
  padding: 1px 6px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-size: var(--text-xs);
  font-weight: 500;
  line-height: 1.5;
  white-space: nowrap;
  color: var(--text-secondary);
}

.primary { color: var(--accent);  border-color: var(--accent-border); }
.success { color: var(--success); }
.warning { color: var(--warning); }
.danger  { color: var(--danger); }
</style>
