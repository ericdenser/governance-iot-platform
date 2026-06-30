<script setup lang="ts">
import AppBadge from '@/components/AppBadge.vue'

withDefaults(defineProps<{
  events: any[]
  showDevice?: boolean
}>(), { showDevice: true })

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'
</script>

<template>
  <table class="tbl">
    <thead>
      <tr>
        <th>Tipo</th>
        <th v-if="showDevice">Dispositivo</th>
        <th>Data</th>
      </tr>
    </thead>
    <tbody>
      <tr v-for="e in events" :key="e.eventId">
        <td><AppBadge variant="info">{{ e.eventType }}</AppBadge></td>
        <td v-if="showDevice" class="text-sm">{{ e.deviceName ?? e.deviceId ?? '—' }}</td>
        <td class="text-muted text-sm">{{ fmt(e.uploadedAt) }}</td>
      </tr>
      <tr v-if="!events.length">
        <td :colspan="showDevice ? 3 : 2" class="empty">Nenhum evento encontrado</td>
      </tr>
    </tbody>
  </table>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
</style>
