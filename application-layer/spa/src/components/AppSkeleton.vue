<script setup lang="ts">
// Placeholder with the shape of what is loading, so the page does not jump when data arrives
withDefaults(defineProps<{
  variant?: 'table' | 'cards' | 'list' | 'detail'
  rows?: number
}>(), { variant: 'table', rows: 5 })
</script>

<template>
  <div class="skeleton" :class="variant" role="status" aria-busy="true">
    <span class="sr-only">Carregando</span>

    <template v-if="variant === 'table'">
      <div v-for="n in rows" :key="n" class="sk-row">
        <span class="sk-bar w-30" /><span class="sk-bar w-20" /><span class="sk-bar w-15" /><span class="sk-bar w-20" />
      </div>
    </template>

    <template v-else-if="variant === 'cards'">
      <div v-for="n in rows" :key="n" class="sk-card">
        <span class="sk-bar w-50 tall" /><span class="sk-bar w-30" />
        <div class="sk-row"><span class="sk-bar w-20" /><span class="sk-bar w-20" /><span class="sk-bar w-20" /></div>
      </div>
    </template>

    <template v-else-if="variant === 'list'">
      <div v-for="n in rows" :key="n" class="sk-line"><span class="sk-bar" :class="n % 2 ? 'w-50' : 'w-30'" /></div>
    </template>

    <template v-else>
      <span class="sk-bar w-30 tall" /><span class="sk-bar w-50" />
      <div class="sk-panel">
        <div v-for="n in rows" :key="n" class="sk-row"><span class="sk-bar w-15" /><span class="sk-bar w-30" /></div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.skeleton { display: flex; flex-direction: column; gap: var(--space-3); width: 100%; }
.skeleton.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); }

.sk-bar {
  display: block; height: 12px; border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--panel) 25%, var(--border) 50%, var(--panel) 75%);
  background-size: 200% 100%;
  animation: sk-shimmer 1.4s ease-in-out infinite;
}
.sk-bar.tall { height: 18px; }
.w-15 { width: 15%; } .w-20 { width: 20%; } .w-30 { width: 30%; } .w-50 { width: 50%; }

.sk-row { display: flex; align-items: center; gap: var(--space-4); padding: var(--space-3) 0; border-top: 1px solid var(--border); }
.sk-row:first-of-type { border-top: none; }
.sk-line { padding: var(--space-2) 0; }
.sk-card { display: flex; flex-direction: column; gap: var(--space-3); padding: var(--space-4); background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-md); }
.sk-card .sk-row { border-top: 1px solid var(--border); padding-bottom: 0; }
.sk-panel { margin-top: var(--space-3); padding: var(--space-2) var(--space-5); background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); }

@keyframes sk-shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }
@media (prefers-reduced-motion: reduce) { .sk-bar { animation: none; } }
</style>
