<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { devicesApi } from '@/services/devices'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const deviceId = route.params.id as string

const device = ref<any>(null)
const tab = ref<'info' | 'commands' | 'events' | 'errors'>('info')
const loading = ref(true)

const commands = ref<any[]>([])
const events = ref<any[]>([])
const errors = ref<any[]>([])
const cmdPage = ref(0); const cmdTotal = ref(0)
const evPage  = ref(0); const evTotal  = ref(0)
const errPage = ref(0); const errTotal = ref(0)

const revoking = ref(false)

const statusVariant = (s: string): any => {
  const m: Record<string, string> = { ACTIVE: 'success', PENDING: 'warning', PROVISIONING: 'info', COMMAND_PENDING: 'info', REVOKED: 'danger' }
  return m[s] ?? 'muted'
}
const cmdVariant = (s: string): any => ({ PENDING: 'warning', COMPLETED: 'success', FAILED: 'danger', TIMEOUT: 'danger' }[s] ?? 'muted')
const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const loadCommands = async () => {
  const r = await devicesApi.getCommands(deviceId, cmdPage.value)
  commands.value = r.data.content ?? []; cmdTotal.value = r.data.page?.totalPages ?? 1
}
const loadEvents = async () => {
  const r = await devicesApi.getEvents(deviceId, evPage.value)
  events.value = r.data.content ?? []; evTotal.value = r.data.page?.totalPages ?? 1
}
const loadErrors = async () => {
  const r = await devicesApi.getErrors(deviceId, errPage.value)
  errors.value = r.data.content ?? []; errTotal.value = r.data.page?.totalPages ?? 1
}

const switchTab = async (t: typeof tab.value) => {
  tab.value = t
  if (t === 'commands' && !commands.value.length) await loadCommands()
  if (t === 'events'   && !events.value.length)   await loadEvents()
  if (t === 'errors'   && !errors.value.length)    await loadErrors()
}

const revoke = async () => {
  if (!confirm('Revogar este dispositivo? Esta ação não pode ser desfeita.')) return
  revoking.value = true
  try { await devicesApi.revoke(deviceId); await loadDevice() }
  finally { revoking.value = false }
}

const loadDevice = async () => {
  const r = await devicesApi.get(deviceId)
  device.value = r.data
}

onMounted(async () => {
  try { await loadDevice() } catch { router.push('/devices') }
  finally { loading.value = false }
})
</script>

<template>
  <AppLayout>
    <div v-if="loading" class="loading">Carregando...</div>
    <div v-else-if="device" class="detail">

      <div class="detail-header">
        <div>
          <button class="back-btn" @click="$router.push('/devices')">← Dispositivos</button>
          <h1 class="device-name">{{ device.name }}</h1>
          <div class="device-meta">
            <AppBadge :variant="statusVariant(device.status)" dot>{{ device.status }}</AppBadge>
            <span class="mono text-muted text-sm">{{ device.deviceId }}</span>
          </div>
        </div>
        <AppButton v-if="authStore.isAdmin && device.status !== 'REVOKED'"
                   variant="danger" size="sm" :loading="revoking" @click="revoke">
          Revogar
        </AppButton>
      </div>

      <div class="tabs">
        <button class="tab-btn" :class="{ active: tab === 'info' }"     @click="switchTab('info')">Informações</button>
        <button class="tab-btn" :class="{ active: tab === 'commands' }" @click="switchTab('commands')">Comandos</button>
        <button class="tab-btn" :class="{ active: tab === 'events' }"   @click="switchTab('events')">Eventos</button>
        <button class="tab-btn" :class="{ active: tab === 'errors' }"   @click="switchTab('errors')">Erros</button>
      </div>

      <!-- INFO -->
      <AppCard v-if="tab === 'info'">
        <div class="info-grid">
          <div class="info-row"><span class="info-label">MAC Address</span><span class="mono">{{ device.macAddress ?? '—' }}</span></div>
          <div class="info-row"><span class="info-label">Firmware</span><span>{{ device.firmware?.version ?? '—' }}</span></div>
          <div class="info-row"><span class="info-label">Criado em</span><span>{{ fmt(device.createdAt) }}</span></div>
          <div class="info-row"><span class="info-label">Última atividade</span><span>{{ fmt(device.lastSeen) }}</span></div>
          <div class="info-row"><span class="info-label">Provisionado por</span>
            <span v-if="device.issuedByUsername">
              {{ device.issuedByUsername }}
              <span class="text-muted text-xs"> ({{ device.issuedByActorId }})</span>
            </span>
            <span v-else class="text-muted">—</span>
          </div>
        </div>
      </AppCard>

      <!-- COMMANDS -->
      <AppCard v-if="tab === 'commands'" title="Histórico de Comandos">
        <table class="tbl">
          <thead><tr><th>Tipo</th><th>Status</th><th>Enviado em</th><th>Concluído em</th></tr></thead>
          <tbody>
            <tr v-for="c in commands" :key="c.commandId">
              <td class="mono text-sm">{{ c.commandType }}</td>
              <td><AppBadge :variant="cmdVariant(c.status)">{{ c.status }}</AppBadge></td>
              <td class="text-muted text-sm">{{ fmt(c.sentAt) }}</td>
              <td class="text-muted text-sm">{{ fmt(c.completedAt) }}</td>
            </tr>
            <tr v-if="!commands.length"><td colspan="4" class="empty">Nenhum comando</td></tr>
          </tbody>
        </table>
        <div class="pagination">
          <AppButton size="sm" variant="secondary" :disabled="cmdPage === 0" @click="cmdPage--; loadCommands()">Anterior</AppButton>
          <span class="text-muted text-sm">Página {{ cmdPage + 1 }}</span>
          <AppButton size="sm" variant="secondary" :disabled="cmdPage + 1 >= cmdTotal" @click="cmdPage++; loadCommands()">Próxima</AppButton>
        </div>
      </AppCard>

      <!-- EVENTS -->
      <AppCard v-if="tab === 'events'" title="Eventos">
        <table class="tbl">
          <thead><tr><th>Tipo</th><th>Data</th></tr></thead>
          <tbody>
            <tr v-for="e in events" :key="e.id">
              <td><AppBadge variant="info">{{ e.eventType }}</AppBadge></td>
              <td class="text-muted text-sm">{{ fmt(e.uploadedAt) }}</td>
            </tr>
            <tr v-if="!events.length"><td colspan="2" class="empty">Nenhum evento</td></tr>
          </tbody>
        </table>
        <div class="pagination">
          <AppButton size="sm" variant="secondary" :disabled="evPage === 0" @click="evPage--; loadEvents()">Anterior</AppButton>
          <span class="text-muted text-sm">Página {{ evPage + 1 }}</span>
          <AppButton size="sm" variant="secondary" :disabled="evPage + 1 >= evTotal" @click="evPage++; loadEvents()">Próxima</AppButton>
        </div>
      </AppCard>

      <!-- ERRORS -->
      <AppCard v-if="tab === 'errors'" title="Erros">
        <table class="tbl">
          <thead><tr><th>Código</th><th>Mensagem</th><th>Data</th></tr></thead>
          <tbody>
            <tr v-for="e in errors" :key="e.id">
              <td><AppBadge variant="danger">{{ e.errorCode }}</AppBadge></td>
              <td class="text-sm">{{ e.message ?? '—' }}</td>
              <td class="text-muted text-sm">{{ fmt(e.timestamp) }}</td>
            </tr>
            <tr v-if="!errors.length"><td colspan="3" class="empty">Nenhum erro</td></tr>
          </tbody>
        </table>
        <div class="pagination">
          <AppButton size="sm" variant="secondary" :disabled="errPage === 0" @click="errPage--; loadErrors()">Anterior</AppButton>
          <span class="text-muted text-sm">Página {{ errPage + 1 }}</span>
          <AppButton size="sm" variant="secondary" :disabled="errPage + 1 >= errTotal" @click="errPage++; loadErrors()">Próxima</AppButton>
        </div>
      </AppCard>
    </div>
  </AppLayout>
</template>

<style scoped>
.loading { color: var(--text-muted); padding: var(--space-8); text-align: center; }
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; }
.back-btn { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--space-2); padding: 0; }
.back-btn:hover { color: var(--text); }
.device-name { font-size: var(--text-2xl); font-weight: 700; color: var(--text); margin: 0 0 var(--space-2); }
.device-meta { display: flex; align-items: center; gap: var(--space-3); }

.tabs { display: flex; gap: var(--space-1); border-bottom: 1px solid var(--border); padding-bottom: 0; }
.tab-btn { background: none; border: none; border-bottom: 2px solid transparent; color: var(--text-muted); cursor: pointer; font-size: var(--text-sm); font-weight: 500; padding: var(--space-2) var(--space-4); margin-bottom: -1px; transition: all var(--transition); }
.tab-btn:hover { color: var(--text); }
.tab-btn.active { color: var(--primary); border-bottom-color: var(--primary); }

.info-grid { display: flex; flex-direction: column; gap: 0; }
.info-row { display: flex; align-items: center; padding: var(--space-3) 0; border-bottom: 1px solid var(--border); gap: var(--space-4); }
.info-row:last-child { border-bottom: none; }
.info-label { width: 180px; font-size: var(--text-sm); color: var(--text-muted); flex-shrink: 0; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 0 var(--space-3); text-align: left; }
.tbl td { padding: var(--space-3) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-6) 0; }
.pagination { display: flex; align-items: center; justify-content: center; gap: var(--space-4); margin-top: var(--space-4); }
</style>
