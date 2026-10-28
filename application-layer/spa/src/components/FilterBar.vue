<script setup lang="ts">
import { ref, watch } from 'vue'

// Search text + date range shared by the event, error and audit lists.
// Extra selects go in the default slot. Emits 'change' (debounced for typing).
export interface DateRange { from?: string; to?: string }

const props = defineProps<{ placeholder?: string }>()
const emit = defineEmits<{ (e: 'change', value: { q: string } & DateRange): void }>()

const q = ref('')
const fromDate = ref('')
const toDate = ref('')
let timer: ReturnType<typeof setTimeout> | null = null

// Dates are picked in local time; the API filters on instants
const startOfDay = (d: string) => (d ? new Date(`${d}T00:00:00`).toISOString() : undefined)
const endOfDay = (d: string) => (d ? new Date(`${d}T23:59:59.999`).toISOString() : undefined)

const emitNow = () => emit('change', { q: q.value.trim(), from: startOfDay(fromDate.value), to: endOfDay(toDate.value) })

watch(q, () => {
  if (timer) clearTimeout(timer)
  timer = setTimeout(emitNow, 300)
})
watch([fromDate, toDate], emitNow)

const clear = () => {
  q.value = ''
  fromDate.value = ''
  toDate.value = ''
}

defineExpose({ clear })
</script>

<template>
  <div class="filter-bar">
    <input v-model="q" type="search" class="field search" :placeholder="props.placeholder ?? 'Buscar'" />
    <slot />
    <label class="date">
      <span>De</span>
      <input v-model="fromDate" type="date" class="field" :max="toDate || undefined" />
    </label>
    <label class="date">
      <span>Até</span>
      <input v-model="toDate" type="date" class="field" :min="fromDate || undefined" />
    </label>
  </div>
</template>

<style scoped>
.filter-bar { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); }
.field, :slotted(.field) {
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: 7px 10px;
  font-family: var(--font-sans);
  font-size: var(--text-sm);
  color: var(--text);
  outline: none;
  color-scheme: dark;
}
.field:focus, :slotted(.field:focus) { border-color: var(--primary); }
.search { flex: 1 1 240px; min-width: 200px; }
.date { display: inline-flex; align-items: center; gap: var(--space-2); font-size: var(--text-xs); color: var(--text-muted); }
:slotted(select.field) { min-width: 160px; }
</style>
