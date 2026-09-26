<script setup lang="ts">
import AppBadge from '@/components/AppBadge.vue'
import type { EventRegistryResponseDTO } from '@/types/models'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

withDefaults(defineProps<{
  events: EventRegistryResponseDTO[]
  showDevice?: boolean
}>(), { showDevice: true })

const fmt = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

// Cor por tipo de evento — destaca crítico (rollback/error) e sucesso (provisioning/OTA).
const eventVariant = (type: string): BadgeVariant => {
  const t = type.toUpperCase()
  if (t.includes('ROLLBACK') || t.includes('FAIL') || t.includes('ERROR') || t.includes('REVOK')) return 'danger'
  if (t.includes('SUCCESS') || t.includes('PROVISION') || t.includes('COMPLETE') || t.includes('ACTIVE')) return 'success'
  if (t.includes('OTA') || t.includes('UPDATE') || t.includes('COMMAND')) return 'info'
  if (t.includes('BATTERY') || t.includes('WARN')) return 'warning'
  return 'muted'
}
</script>

<template>
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
      <tr v-for="e in events" :key="e.eventId">
        <td><AppBadge :variant="eventVariant(e.eventType)">{{ e.eventType }}</AppBadge></td>
        <td v-if="showDevice" class="text-sm">{{ e.deviceName ?? e.deviceId ?? '—' }}</td>
        <td class="text-xs mono text-muted transition-cell">
          <template v-if="e.previousStatus || e.newStatus">
            <span>{{ e.previousStatus ?? '—' }}</span>
            <span class="arrow">→</span>
            <span>{{ e.newStatus ?? '—' }}</span>
          </template>
          <span v-else>—</span>
        </td>
        <td class="text-sm msg-cell" :title="e.resultMessage ?? ''">
          <span v-if="e.resultMessage">{{ e.resultMessage }}</span>
          <span v-else class="text-muted">—</span>
        </td>
        <td class="text-muted text-sm date-cell">{{ fmt(e.occurredAt) }}</td>
      </tr>
      <tr v-if="!events.length">
        <td :colspan="showDevice ? 5 : 4" class="empty">Nenhum evento encontrado</td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; table-layout: fixed; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: top; }

.col-type       { width: 18%; }
.col-device     { width: 18%; }
.col-transition { width: 18%; }
.col-msg        { width: auto; }
.col-date       { width: 15%; }

.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.mono { font-family: var(--font-mono); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }

.transition-cell { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.arrow { opacity: 0.5; }

.msg-cell { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.date-cell { white-space: nowrap; }
</style>
