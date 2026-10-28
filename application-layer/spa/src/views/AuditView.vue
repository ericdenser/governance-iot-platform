<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import AppPagination from '@/components/AppPagination.vue'
import FilterBar from '@/components/FilterBar.vue'
import { auditApi, type AuditFilters } from '@/services/audit'
import type { AuditLogResponseDTO } from '@/types/models'


const entries = ref<AuditLogResponseDTO[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)

const fmt = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

// AuditAction enum in the govApi
const ACTIONS = [
  'COMMAND_SENT', 'FIRMWARE_UPLOADED', 'FIRMWARE_DEPLOYED', 'FIRMWARE_DEPRECATED', 'FIRMWARE_SET_PROVISIONING',
  'FIRMWARE_BINARY_REUPLOADED', 'FIRMWARE_DELETED', 'FIRMWARE_VERSION_DELETED', 'DEVICE_REVOKED',
  'FLASH_PACKAGE_GENERATED', 'SENSOR_REGISTERED', 'GROUP_CREATED', 'GROUP_DELETED', 'DEVICE_ADDED_TO_GROUP',
  'DEVICE_REMOVED_FROM_GROUP', 'USER_ASSIGNED_TO_GROUP', 'USER_REMOVED_FROM_GROUP', 'DEVICE_DELETED',
]
const filters = ref<AuditFilters>({})
const action = ref('')

const applyFilters = (f: AuditFilters) => { filters.value = f; page.value = 0; load() }
const changeAction = () => { page.value = 0; load() }

const load = async () => {
  const r = await auditApi.list(page.value, { ...filters.value, action: action.value })
  entries.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}
const changePage = (p: number) => { page.value = p; load() }

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard>
      <FilterBar class="filters" placeholder="Buscar por ID, usuário ou ID do alvo" @change="applyFilters">
        <select v-model="action" class="field" @change="changeAction">
          <option value="">Todas as ações</option>
          <option v-for="a in ACTIONS" :key="a" :value="a">{{ a }}</option>
        </select>
      </FilterBar>

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
              <StatusLabel kind="result" :code="e.success ? 'OK' : 'FAIL'" />
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
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: top; }
.mono { font-family: var(--font-mono); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.filters { margin-bottom: var(--space-5); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.action-cell { white-space: nowrap; }
.actor-id { display: block; }
.error-msg { display: block; cursor: help; }
</style>
