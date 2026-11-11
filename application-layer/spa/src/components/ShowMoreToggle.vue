<script setup lang="ts">
defineProps<{ canMore: boolean; canLess: boolean; loading?: boolean }>()
defineEmits<{ (e: 'more'): void; (e: 'less'): void }>()
</script>

<template>
  <div v-if="canMore || canLess" class="toggle-row">
    <button v-if="canLess" type="button" class="toggle" title="Mostrar menos" aria-label="Mostrar menos" @click="$emit('less')">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15" /></svg>
      Menos
    </button>
    <button v-if="canMore" type="button" class="toggle" title="Mostrar mais antigos" aria-label="Mostrar mais antigos" :disabled="loading" @click="$emit('more')">
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="6 9 12 15 18 9" /></svg>
      {{ loading ? 'Carregando...' : 'Mais' }}
    </button>
  </div>
</template>

<style scoped>
.toggle-row { display: flex; justify-content: center; gap: var(--space-2); padding-top: var(--space-3); border-top: 1px solid var(--border); margin-top: var(--space-2); }
.toggle { display: inline-flex; align-items: center; gap: 4px; background: none; border: 1px solid var(--border); border-radius: var(--radius-pill); padding: 4px 12px; font-family: var(--font-sans); font-size: var(--text-xs); color: var(--text-muted); cursor: pointer; transition: all var(--transition); }
.toggle:hover:not(:disabled) { color: var(--text); border-color: var(--border-strong); }
.toggle:disabled { cursor: default; opacity: 0.6; }
</style>
