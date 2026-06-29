<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { eventsApi } from '@/services/events'

const events = ref<any[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)

const eventVariant = (t: string): any => {
  if (t?.includes('ERROR') || t?.includes('FAIL')) return 'danger'
  if (t?.includes('OTA') || t?.includes('UPDATE') || t?.includes('FIRMWARE')) return 'info'
  if (t?.includes('ACTIVE') || t?.includes('SUCCESS')) return 'success'
  return 'muted'
}

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const load = async () => {
  const r = await eventsApi.list(page.value)
  events.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Eventos" description="Histórico de eventos reportados pelos dispositivos">
      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Tipo</th><th>Device ID</th><th>Data</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="e in events" :key="e.id">
            <td><AppBadge :variant="eventVariant(e.eventType)">{{ e.eventType }}</AppBadge></td>
            <td class="mono text-sm">{{ e.deviceId ?? '—' }}</td>
            <td class="text-muted text-sm">{{ fmt(e.uploadedAt) }}</td>
          </tr>
          <tr v-if="!events.length"><td colspan="3" class="empty">Nenhum evento encontrado</td></tr>
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
