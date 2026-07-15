<script setup lang="ts">
import { ref, watch } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import DeviceMap from '@/components/DeviceMap.vue'
import LiveIndicator from '@/components/LiveIndicator.vue'
import type { DeviceStatus } from '@/types/models'

const STORAGE_KEY = 'map_view_config'
const ALL_STATUSES: DeviceStatus[] = ['ACTIVE', 'PENDING', 'PROVISIONING', 'COMMAND_PENDING', 'REVOKED', 'ERROR']

interface MapConfig {
  statusFilter: string[]
  autoFit: boolean
}

const loadConfig = (): MapConfig => {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) {
      const c = JSON.parse(raw)
      return {
        statusFilter: Array.isArray(c.statusFilter) ? c.statusFilter : [],
        autoFit: c.autoFit !== false,
      }
    }
  } catch { /* config corrompida — usa default */ }
  return { statusFilter: [], autoFit: true }
}

const config = ref<MapConfig>(loadConfig())

watch(config, (c) => localStorage.setItem(STORAGE_KEY, JSON.stringify(c)), { deep: true })

const toggleStatus = (s: string) => {
  const i = config.value.statusFilter.indexOf(s)
  if (i >= 0) config.value.statusFilter.splice(i, 1)
  else config.value.statusFilter.push(s)
}

const clearFilter = () => { config.value.statusFilter = [] }
</script>

<template>
  <AppLayout>
    <div class="map-page">
      <div class="map-toolbar">
        <div class="chips">
          <button
            class="chip"
            :class="{ active: !config.statusFilter.length }"
            @click="clearFilter"
          >Todos</button>
          <button
            v-for="s in ALL_STATUSES"
            :key="s"
            class="chip"
            :class="{ active: config.statusFilter.includes(s) }"
            :data-status="s"
            @click="toggleStatus(s)"
          >
            <span class="chip-dot" />
            {{ s }}
          </button>
        </div>
        <div class="toolbar-right">
          <label class="autofit-toggle">
            <input v-model="config.autoFit" type="checkbox" />
            Auto-ajustar zoom
          </label>
          <LiveIndicator />
        </div>
      </div>

      <div class="map-container">
        <DeviceMap
          :status-filter="config.statusFilter"
          :auto-fit="config.autoFit"
          :scroll-wheel-zoom="true"
        />
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.map-page {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
  height: calc(100vh - var(--header-height) - var(--space-6) * 2);
  min-height: 400px;
}

.map-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.chips {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--space-1) var(--space-3);
  border-radius: var(--radius-pill);
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-muted);
  font-family: var(--font-sans);
  font-size: var(--text-xs);
  font-weight: 500;
  cursor: pointer;
  transition: all var(--transition);
}

.chip:hover { color: var(--text); background: var(--panel); }

.chip.active {
  color: var(--primary);
  background: var(--primary-dim);
  border-color: var(--primary-border);
}

.chip-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--status-unknown);
}
.chip[data-status="ACTIVE"] .chip-dot { background: var(--status-active); }
.chip[data-status="PENDING"] .chip-dot { background: var(--status-pending); }
.chip[data-status="PROVISIONING"] .chip-dot { background: var(--status-provisioning); }
.chip[data-status="COMMAND_PENDING"] .chip-dot { background: var(--status-command-pending); }
.chip[data-status="REVOKED"] .chip-dot { background: var(--status-revoked); }
.chip[data-status="ERROR"] .chip-dot { background: var(--status-error); }

.toolbar-right {
  display: flex;
  align-items: center;
  gap: var(--space-4);
}

.autofit-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--text-xs);
  color: var(--text-muted);
  cursor: pointer;
  white-space: nowrap;
}

.map-container {
  flex: 1;
  min-height: 0;
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  overflow: hidden;
}
</style>
