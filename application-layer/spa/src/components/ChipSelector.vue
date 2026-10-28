<script setup lang="ts">
import { CHIP_LIST, chipLabel } from '@/utils/chips'
import type { TargetChip } from '@/types/models'

// Segmented control: one tab per supported chip
const model = defineModel<TargetChip>({ required: true })
</script>

<template>
  <div class="chip-tabs" role="tablist" aria-label="Chip">
    <button
      v-for="chip in CHIP_LIST"
      :key="chip"
      type="button"
      role="tab"
      class="tab"
      :class="{ active: chip === model }"
      :aria-selected="chip === model"
      @click="model = chip"
    >{{ chipLabel(chip) }}</button>
  </div>
</template>

<style scoped>
.chip-tabs {
  display: inline-flex;
  padding: 2px;
  background: var(--panel);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}
.tab {
  padding: 5px var(--space-3);
  background: none;
  border: none;
  border-radius: var(--radius-sm);
  color: var(--text-muted);
  font-family: var(--font-mono);
  font-size: var(--text-sm);
  cursor: pointer;
  transition: background var(--transition), color var(--transition);
}
.tab:hover { color: var(--text); }
.tab.active { background: var(--surface); color: var(--text); box-shadow: var(--shadow-sm); }
</style>
