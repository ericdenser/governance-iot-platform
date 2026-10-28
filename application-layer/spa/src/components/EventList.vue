<script setup lang="ts">
import { ref } from 'vue'
import StatusLabel from '@/components/StatusLabel.vue'
import type { EventRegistryResponseDTO } from '@/types/models'
import { absoluteTime } from '@/utils/format'

withDefaults(defineProps<{
  events: EventRegistryResponseDTO[]
  showDevice?: boolean
}>(), { showDevice: true })

// Descricao fica em 2 linhas; clique na linha mostra o texto inteiro
const expanded = ref(new Set<string>())
const toggle = (id: string) => {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id)
  else next.add(id)
  expanded.value = next
}
</script>

<template>
  <div class="event-list">
    <table class="tbl">
      <thead>
        <tr>
          <th class="col-type">Tipo</th>
          <th v-if="showDevice" class="col-device">Dispositivo</th>
          <th class="col-transition">Transição</th>
          <th class="col-msg">Descrição</th>
          <th class="col-date">Data</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="e in events" :key="e.eventId" class="row"
            :class="{ open: expanded.has(e.eventId) }" @click="toggle(e.eventId)">
          <td class="cell-type"><StatusLabel kind="event" :code="e.eventType" /></td>
          <td v-if="showDevice" class="cell-device text-sm">{{ e.deviceName ?? e.deviceId ?? '—' }}</td>
          <td class="cell-transition text-xs mono text-muted">
            <template v-if="e.previousStatus || e.newStatus">
              {{ e.previousStatus ?? '—' }} <span class="arrow">→</span> {{ e.newStatus ?? '—' }}
            </template>
            <template v-else>—</template>
          </td>
          <td class="cell-msg text-sm">
            <span v-if="e.resultMessage" class="msg">{{ e.resultMessage }}</span>
            <span v-else class="text-muted">—</span>
          </td>
          <td class="cell-date text-muted text-sm">{{ absoluteTime(e.occurredAt) }}</td>
        </tr>
        <tr v-if="!events.length">
          <td :colspan="showDevice ? 5 : 4" class="empty">Nenhum evento encontrado</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.event-list { container-type: inline-size; }

.tbl { width: 100%; border-collapse: collapse; table-layout: fixed; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: top; overflow-wrap: anywhere; }

.col-type       { width: 20%; }
.col-device     { width: 14%; }
.col-transition { width: 20%; }
.col-msg        { width: auto; }
.col-date       { width: 16%; }

.row { cursor: pointer; }
.row:hover td { background: var(--muted); }
.cell-type :deep(.status) { white-space: normal; align-items: flex-start; }
.arrow { opacity: 0.5; }
.cell-date { white-space: nowrap; }

.msg { display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; line-clamp: 2; overflow: hidden; }
.row.open .msg { display: inline; -webkit-line-clamp: unset; line-clamp: unset; }

.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.mono { font-family: var(--font-mono); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; cursor: default; }

/* Estreito: cada evento vira um bloco empilhado */
@container (max-width: 720px) {
  .tbl, .tbl tbody, .tbl tr, .tbl td { display: block; width: 100%; }
  .tbl thead { display: none; }
  .row {
    display: grid;
    grid-template-columns: 1fr auto;
    gap: 4px var(--space-2);
    padding: var(--space-3) 0;
    border-top: 1px solid var(--border);
  }
  .tbl td { padding: 0; border-top: none; }
  .cell-type { grid-column: 1; }
  .cell-date { grid-column: 2; grid-row: 1; text-align: right; }
  .cell-device, .cell-transition, .cell-msg { grid-column: 1 / -1; }
}
</style>
