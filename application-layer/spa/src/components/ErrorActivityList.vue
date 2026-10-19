<script setup lang="ts">
import StatusLabel from '@/components/StatusLabel.vue'
import type { ErrorRecordResponseDTO } from '@/types/models'
import { absoluteTime, relativeTime } from '@/utils/format'

defineProps<{ errors: ErrorRecordResponseDTO[] }>()
</script>

<template>
  <ul class="error-list">
    <li v-for="e in errors" :key="e.errorId" class="error-item">
      <div class="item-badges">
        <span class="code-tag">{{ e.error }}</span>
        <StatusLabel kind="error" :code="e.status" />
      </div>
      <div class="item-meta">
        <span class="device-name">{{ e.deviceName ?? e.deviceId ?? '—' }}</span>
        <span class="sep">·</span>
        <span class="date" :title="absoluteTime(e.reportedAt)">{{ relativeTime(e.reportedAt) }}</span>
        <template v-if="e.fixedAt">
          <span class="sep">·</span>
          <span class="fixed-at" :title="absoluteTime(e.fixedAt)">resolvido {{ relativeTime(e.fixedAt) }}</span>
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
.item-badges { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); flex-wrap: wrap; }
.item-meta { display: flex; align-items: center; gap: 4px; flex-wrap: wrap; font-size: var(--text-xs); color: var(--text-muted); min-width: 0; }
.device-name { font-weight: 500; color: var(--text-secondary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sep { flex-shrink: 0; }
.date, .fixed-at { flex-shrink: 0; white-space: nowrap; }
.empty { color: var(--text-muted); font-size: var(--text-sm); padding: var(--space-2) 0; }
</style>
