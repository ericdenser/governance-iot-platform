<script setup lang="ts">
import AppBadge from '@/components/AppBadge.vue'
import type { ErrorRecordResponseDTO, ErrorStatus } from '@/types/models'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

defineProps<{ errors: ErrorRecordResponseDTO[] }>()

const statusVariant = (s: ErrorStatus): BadgeVariant =>
  (({ FIXED: 'success', RETRY_FAILED: 'danger', NOT_FIXABLE: 'danger' } as Record<ErrorStatus, BadgeVariant>)[s] ?? 'warning')

const fmt = (iso: string) => iso ? new Date(iso).toLocaleDateString('pt-BR') : '—'
</script>

<template>
  <ul class="error-list">
    <li v-for="e in errors" :key="e.errorId" class="error-item">
      <div class="item-badges">
        <AppBadge variant="danger">{{ e.error }}</AppBadge>
        <AppBadge :variant="statusVariant(e.status)">{{ e.status }}</AppBadge>
      </div>
      <div class="item-meta">
        <span class="device-name">{{ e.deviceName ?? e.deviceId ?? '—' }}</span>
        <span class="sep">·</span>
        <span class="date">{{ fmt(e.reportedAt) }}</span>
        <template v-if="e.fixedAt">
          <span class="sep">·</span>
          <span class="fixed-at">fixado {{ fmt(e.fixedAt) }}</span>
        </template>
      </div>
    </li>
    <li v-if="!errors.length" class="empty">Sem erros recentes</li>
  </ul>
</template>

<style scoped>
.error-list { list-style: none; display: flex; flex-direction: column; margin: 0; padding: 0; }
.error-item { display: flex; flex-direction: column; gap: 4px; padding: var(--space-3) 0; border-bottom: 1px solid var(--border); }
.error-item:last-child { border-bottom: none; }
.item-badges { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
.item-meta { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; font-size: var(--text-xs); color: var(--text-muted); min-width: 0; }
.device-name { font-weight: 500; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sep { flex-shrink: 0; }
.date, .fixed-at { flex-shrink: 0; white-space: nowrap; }
.empty { color: var(--text-muted); font-size: var(--text-sm); padding: var(--space-2) 0; }
</style>
