<script setup lang="ts">
import { ref } from 'vue'
import StatusLabel from '@/components/StatusLabel.vue'
import type { ErrorRecordResponseDTO } from '@/types/models'
import { absoluteTime } from '@/utils/format'

// Same list on the Errors page and on the device page (showDevice=false)
withDefaults(defineProps<{
  errors: ErrorRecordResponseDTO[]
  showDevice?: boolean
}>(), { showDevice: true })

const expanded = ref(new Set<string>())
const toggle = (id: string) => {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}
</script>

<template>
  <table class="tbl">
    <thead>
      <tr>
        <th>Código</th>
        <th>Status</th>
        <th v-if="showDevice">Dispositivo</th>
        <th>Reportado em</th>
        <th>Resolvido em</th>
        <th></th>
      </tr>
    </thead>
    <tbody>
      <template v-for="e in errors" :key="e.errorId">
        <tr class="row" :class="{ clickable: e.details || e.message }" @click="(e.details || e.message) && toggle(e.errorId)">
          <td><span class="code-tag">{{ e.error }}</span></td>
          <td><StatusLabel kind="error" :code="e.status" /></td>
          <td v-if="showDevice">{{ e.deviceName ?? e.deviceId ?? '—' }}</td>
          <td class="text-muted">{{ absoluteTime(e.reportedAt) }}</td>
          <td class="text-muted">{{ absoluteTime(e.fixedAt) }}</td>
          <td class="cell-toggle">
            <span v-if="e.details || e.message" class="chevron" :class="{ open: expanded.has(e.errorId) }">▾</span>
          </td>
        </tr>
        <tr v-if="expanded.has(e.errorId)" class="detail-row">
          <td :colspan="showDevice ? 6 : 5">
            <div class="detail-box">
              <p v-if="e.message" class="detail-message">{{ e.message }}</p>
              <pre v-if="e.details" class="detail-pre">{{ e.details }}</pre>
            </div>
          </td>
        </tr>
      </template>
      <tr v-if="!errors.length">
        <td :colspan="showDevice ? 6 : 5" class="empty">Nenhum erro encontrado</td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: middle; font-size: var(--text-sm); }
.row.clickable { cursor: pointer; }
.row.clickable:hover td { background: var(--muted); }
.text-muted { color: var(--text-muted); }
.cell-toggle { width: 1%; text-align: right; }
.chevron { display: inline-block; color: var(--text-muted); transition: transform var(--transition); }
.chevron.open { transform: rotate(180deg); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }

.detail-row td { background: var(--panel); border-top: none; padding: 0; }
.detail-box { padding: var(--space-3) var(--space-4); display: flex; flex-direction: column; gap: var(--space-2); border-left: 2px solid var(--danger); margin: var(--space-1) 0 var(--space-2); }
.detail-message { font-size: var(--text-sm); color: var(--text-secondary); margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; line-height: 1.5; }
.detail-pre { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); white-space: pre-wrap; word-break: break-all; margin: 0; line-height: 1.5; }
</style>
