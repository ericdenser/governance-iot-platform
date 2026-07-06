<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppPagination from '@/components/AppPagination.vue'
import { errorsApi } from '@/services/errors'
import type { ErrorRecordResponseDTO, ErrorStatus } from '@/types/models'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

const errors = ref<ErrorRecordResponseDTO[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)
const expanded = ref<Set<string>>(new Set())

const statusVariant = (s: ErrorStatus): BadgeVariant =>
  (({ FIXED: 'success', RETRY_FAILED: 'danger', NOT_FIXABLE: 'danger' } as Record<ErrorStatus, BadgeVariant>)[s] ?? 'warning')

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const toggleExpand = (id: string) => {
  const next = new Set(expanded.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  expanded.value = next
}

const load = async () => {
  expanded.value = new Set()
  const r = await errorsApi.list(page.value)
  errors.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}
const changePage = (p: number) => { page.value = p; load() }

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Erros" description="Erros reportados pelos dispositivos">
      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Código</th>
            <th>Status</th>
            <th>Dispositivo</th>
            <th>Reportado em</th>
            <th>Fixado em</th>
            <th>Detalhes</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="e in errors" :key="e.errorId">
            <tr>
              <td><AppBadge variant="danger">{{ e.error }}</AppBadge></td>
              <td><AppBadge :variant="statusVariant(e.status)">{{ e.status }}</AppBadge></td>
              <td class="text-sm">{{ e.deviceName ?? e.deviceId ?? '—' }}</td>
              <td class="text-muted text-sm">{{ fmt(e.reportedAt) }}</td>
              <td class="text-muted text-sm">{{ fmt(e.fixedAt) }}</td>
              <td>
                <button
                  v-if="e.details || e.message"
                  class="expand-btn"
                  :class="{ open: expanded.has(e.errorId) }"
                  @click="toggleExpand(e.errorId)"
                  :title="expanded.has(e.errorId) ? 'Fechar detalhes' : 'Ver detalhes'"
                >
                  <span class="expand-icon">{{ expanded.has(e.errorId) ? '▴' : '▾' }}</span>
                </button>
                <span v-else class="text-muted text-sm">—</span>
              </td>
            </tr>
            <tr v-if="expanded.has(e.errorId)" class="detail-row">
              <td colspan="6">
                <div class="detail-box">
                  <p v-if="e.message" class="detail-message">{{ e.message }}</p>
                  <pre v-if="e.details" class="detail-pre">{{ e.details }}</pre>
                </div>
              </td>
            </tr>
          </template>
          <tr v-if="!errors.length"><td colspan="6" class="empty">Nenhum erro encontrado</td></tr>
        </tbody>
      </table>

      <AppPagination :page="page" :total-pages="totalPages" @change="changePage" />
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: middle; }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }

.expand-btn { background: none; border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 2px 6px; cursor: pointer; color: var(--text-muted); font-size: var(--text-xs); transition: border-color var(--transition), color var(--transition); }
.expand-btn:hover, .expand-btn.open { border-color: var(--primary); color: var(--primary); }
.expand-icon { display: inline-block; }

.detail-row td { background: var(--panel); border-top: none; padding: 0; }
.detail-box { padding: var(--space-3) var(--space-4); display: flex; flex-direction: column; gap: var(--space-2); border-left: 2px solid var(--danger); margin: var(--space-1) 0 var(--space-2); }
.detail-message { font-size: var(--text-sm); color: var(--text-secondary); margin: 0; }
.detail-pre { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); white-space: pre-wrap; word-break: break-all; margin: 0; line-height: 1.5; }
</style>
