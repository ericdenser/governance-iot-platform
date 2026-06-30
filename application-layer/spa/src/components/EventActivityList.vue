<script setup lang="ts">
import AppBadge from '@/components/AppBadge.vue'

defineProps<{ events: any[] }>()

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'
</script>

<template>
  <ul class="activity-list">
    <li v-for="e in events" :key="e.eventId" class="activity-item">
      <div class="item-top">
        <AppBadge variant="info">{{ e.eventType }}</AppBadge>
        <span class="date">{{ fmt(e.uploadedAt) }}</span>
      </div>
      <span class="device-name">{{ e.deviceName ?? e.deviceId ?? '—' }}</span>
    </li>
    <li v-if="!events.length" class="empty">Sem eventos recentes</li>
  </ul>
</template>

<style scoped>
.activity-list { list-style: none; display: flex; flex-direction: column; margin: 0; padding: 0; }
.activity-item { display: flex; flex-direction: column; gap: 4px; padding: var(--space-3) 0; border-bottom: 1px solid var(--border); }
.activity-item:last-child { border-bottom: none; }
.item-top { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
.date { font-size: var(--text-xs); color: var(--text-muted); white-space: nowrap; flex-shrink: 0; }
.device-name { font-size: var(--text-sm); color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.empty { color: var(--text-muted); font-size: var(--text-sm); padding: var(--space-2) 0; }
</style>
