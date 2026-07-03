<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppPagination from '@/components/AppPagination.vue'
import { auditApi } from '@/services/audit'
import type { AuditLogResponseDTO } from '@/types/models'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

const entries = ref<AuditLogResponseDTO[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const successVariant = (ok: boolean): BadgeVariant => ok ? 'success' : 'danger'

const load = async () => {
  const r = await auditApi.list(page.value)
  entries.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}
const changePage = (p: number) => { page.value = p; load() }

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Audit Log" description="Registro de todas as ações administrativas realizadas no sistema">
      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Ação</th><th>Ator</th><th>Tipo Alvo</th><th>ID Alvo</th><th>Resultado</th><th>Data</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in entries" :key="e.auditId">
            <td class="mono text-sm action-cell">{{ e.action }}</td>
            <td class="text-sm">
              <span>{{ e.actorUsername ?? e.actorId }}</span>
              <span v-if="e.actorUsername" class="actor-id text-muted text-xs">{{ e.actorId }}</span>
            </td>
            <td class="text-sm text-muted">{{ e.targetType ?? '—' }}</td>
            <td class="mono text-xs text-muted">{{ e.targetId ?? '—' }}</td>
            <td>
              <AppBadge :variant="successVariant(e.success)">{{ e.success ? 'OK' : 'FALHA' }}</AppBadge>
              <span v-if="!e.success && e.errorMessage" class="error-msg text-xs text-muted" :title="e.errorMessage">
                {{ e.errorMessage.slice(0, 40) }}{{ e.errorMessage.length > 40 ? '…' : '' }}
              </span>
            </td>
            <td class="text-muted text-sm">{{ fmt(e.performedAt) }}</td>
          </tr>
          <tr v-if="!entries.length"><td colspan="6" class="empty">Nenhum registro encontrado</td></tr>
        </tbody>
      </table>

      <AppPagination :page="page" :total-pages="totalPages" @change="changePage" />
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: top; }
.mono { font-family: var(--font-mono); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.action-cell { white-space: nowrap; }
.actor-id { display: block; }
.error-msg { display: block; cursor: help; }
</style>
