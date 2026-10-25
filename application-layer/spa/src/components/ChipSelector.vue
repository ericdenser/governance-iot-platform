<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import type { TargetChip } from '@/types/models'
import { CHIP_LIST, chipLabel } from '@/utils/chips'

const model = defineModel<TargetChip>({ required: true })

const open = ref(false)
const root = ref<HTMLElement | null>(null)

const pick = (chip: TargetChip) => {
  model.value = chip
  open.value = false
}

const onClickOutside = (e: MouseEvent) => {
  if (root.value && !root.value.contains(e.target as Node)) open.value = false
}

onMounted(() => document.addEventListener('click', onClickOutside))
onUnmounted(() => document.removeEventListener('click', onClickOutside))
</script>

<template>
  <div ref="root" class="chip-selector">
    <button type="button" class="trigger" :aria-expanded="open" @click="open = !open">
      <span class="caption">Chip</span>
      <span class="value mono">{{ chipLabel(model) }}</span>
      <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
           stroke-linecap="round" stroke-linejoin="round" :class="{ flipped: open }">
        <polyline points="6 9 12 15 18 9" />
      </svg>
    </button>
    <ul v-if="open" class="menu" role="listbox">
      <li v-for="chip in CHIP_LIST" :key="chip">
        <button type="button" class="option" :class="{ active: chip === model }"
                role="option" :aria-selected="chip === model" @click="pick(chip)">
          {{ chipLabel(chip) }}
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.chip-selector { position: relative; }
.trigger {
  display: inline-flex; align-items: center; gap: var(--space-2);
  height: 100%; min-height: 36px; padding: 0 var(--space-3);
  background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md);
  color: var(--text); font-size: var(--text-sm); cursor: pointer;
  transition: border-color var(--transition);
}
.trigger:hover { border-color: var(--border-strong); }
.caption { color: var(--text-muted); }
.value { font-weight: 500; }
.flipped { transform: rotate(180deg); }
.menu {
  position: absolute; top: calc(100% + 4px); right: 0; z-index: 20; min-width: 100%;
  list-style: none; margin: 0; padding: var(--space-1);
  background: var(--surface); border: 1px solid var(--border-strong); border-radius: var(--radius-md);
  box-shadow: var(--shadow-md);
}
.option {
  width: 100%; text-align: left; padding: 6px var(--space-3);
  background: none; border: none; border-radius: var(--radius-sm);
  color: var(--text-secondary); font-size: var(--text-sm); font-family: var(--font-mono); cursor: pointer;
}
.option:hover { background: var(--panel); color: var(--text); }
.option.active { color: var(--accent); }
</style>
