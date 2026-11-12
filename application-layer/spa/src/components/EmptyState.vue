<script setup lang="ts">
// Empty list: what is missing, why, and the next step (default slot holds the action)
defineProps<{
  title: string
  hint?: string
  compact?: boolean
}>()
</script>

<template>
  <div class="empty-state" :class="{ compact }">
    <p class="empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <div v-if="$slots.default" class="empty-actions"><slot /></div>
  </div>
</template>

<style scoped>
.empty-state { display: flex; flex-direction: column; align-items: center; gap: var(--space-2); padding: var(--space-8) var(--space-4); text-align: center; }
.empty-state.compact { padding: var(--space-5) var(--space-4); }
.empty-title { margin: 0; font-size: var(--text-sm); font-weight: 500; color: var(--text-secondary); }
.empty-hint { margin: 0; max-width: 46ch; font-size: var(--text-sm); color: var(--text-muted); text-wrap: pretty; }
.empty-actions { display: flex; flex-wrap: wrap; justify-content: center; gap: var(--space-2); margin-top: var(--space-2); }
</style>
