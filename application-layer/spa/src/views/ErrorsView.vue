<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { errorsApi } from '@/services/errors'

const errors = ref<any[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const load = async () => {
  const r = await errorsApi.list(page.value)
  errors.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Erros" description="Erros reportados pelos dispositivos">
      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Código</th><th>Mensagem</th><th>Device ID</th><th>Data</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in errors" :key="e.id">
            <td><AppBadge variant="danger">{{ e.errorCode }}</AppBadge></td>
            <td class="text-sm">{{ e.message ?? '—' }}</td>
            <td class="mono text-sm text-muted">{{ e.deviceId ?? '—' }}</td>
            <td class="text-muted text-sm">{{ fmt(e.timestamp) }}</td>
          </tr>
          <tr v-if="!errors.length"><td colspan="4" class="empty">Nenhum erro encontrado</td></tr>
        </tbody>
      </table>

      <div class="pagination">
        <AppButton size="sm" variant="secondary" :disabled="page === 0" @click="page--; load()">Anterior</AppButton>
        <span class="text-muted text-sm">Página {{ page + 1 }} de {{ totalPages }}</span>
        <AppButton size="sm" variant="secondary" :disabled="page + 1 >= totalPages" @click="page++; load()">Próxima</AppButton>
      </div>
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.pagination { display: flex; align-items: center; justify-content: center; gap: var(--space-4); margin-top: var(--space-4); }
</style>
