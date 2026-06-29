<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { commandsApi } from '@/services/commands'
import { devicesApi } from '@/services/devices'
import { firmwareApi } from '@/services/firmware'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const commands = ref<any[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)

// ── Wizard state ──────────────────────────────────────────────────────────────
type Step = 'command' | 'firmware' | 'devices' | 'params' | 'result'

const showWizard = ref(false)
const wizardStep = ref<Step>('command')
const sending = ref(false)
const sendError = ref('')

const selectedCommand = ref('')
const selectedFirmware = ref<any>(null)
const selectedDeviceIds = ref<Set<string>>(new Set())
const durationS = ref(60)

const allDevices = ref<any[]>([])
const allFirmwares = ref<any[]>([])
const loadingDevices = ref(false)
const loadingFirmwares = ref(false)

const sendResult = ref<{ command: string; publishedTo: string[]; failed: string[]; skipped: string[] } | null>(null)

const COMMANDS = [
  { value: 'UPDATE',            label: 'Atualizar Firmware',  desc: 'OTA — instala uma versão específica no device' },
  { value: 'REBOOT',            label: 'Reiniciar',            desc: 'Reinicia o ESP imediatamente' },
  { value: 'DEEP_SLEEP',        label: 'Deep Sleep',           desc: 'Coloca o device em modo de economia por N segundos' },
  { value: 'FIRMWARE_ROLLBACK', label: 'Rollback de Firmware', desc: 'Reverte para a versão anterior registrada no NVS' },
]

// ── Wizard navigation ─────────────────────────────────────────────────────────

const openWizard = async () => {
  selectedCommand.value = ''
  selectedFirmware.value = null
  selectedDeviceIds.value = new Set()
  durationS.value = 60
  sendError.value = ''
  sendResult.value = null
  wizardStep.value = 'command'
  showWizard.value = true
}

const pickCommand = async (cmd: string) => {
  selectedCommand.value = cmd
  if (cmd === 'UPDATE') {
    wizardStep.value = 'firmware'
    await loadFirmwares()
  } else {
    wizardStep.value = 'devices'
    await loadDevices()
  }
}

const pickFirmware = async (fw: any) => {
  selectedFirmware.value = fw
  wizardStep.value = 'devices'
  await loadDevices()
}

const finishDevices = () => {
  if (!selectedDeviceIds.value.size) { sendError.value = 'Selecione ao menos um device.'; return }
  sendError.value = ''
  if (selectedCommand.value === 'DEEP_SLEEP') {
    wizardStep.value = 'params'
  } else {
    doSend()
  }
}

const toggleDevice = (deviceId: string) => {
  const next = new Set(selectedDeviceIds.value)
  next.has(deviceId) ? next.delete(deviceId) : next.add(deviceId)
  selectedDeviceIds.value = next
}

const loadDevices = async () => {
  if (allDevices.value.length) return
  loadingDevices.value = true
  try {
    const r = await devicesApi.list()
    allDevices.value = Array.isArray(r.data) ? r.data : (r.data?.content ?? [])
  } finally { loadingDevices.value = false }
}

const loadFirmwares = async () => {
  if (allFirmwares.value.length) return
  loadingFirmwares.value = true
  try {
    const r = await firmwareApi.list()
    allFirmwares.value = Array.isArray(r.data) ? r.data : []
  } finally { loadingFirmwares.value = false }
}

// ── Send ──────────────────────────────────────────────────────────────────────

const doSend = async () => {
  sending.value = true; sendError.value = ''
  try {
    const payload: any = {
      command: selectedCommand.value,
      targetDevices: [...selectedDeviceIds.value],
    }
    if (selectedCommand.value === 'UPDATE') {
      payload.params = { firmwareId: selectedFirmware.value.firmwareId }
    } else if (selectedCommand.value === 'DEEP_SLEEP') {
      payload.params = { duration_s: Number(durationS.value) }
    }
    const r = await commandsApi.send(payload)
    sendResult.value = r.data
    wizardStep.value = 'result'
    page.value = 0; await load()
  } catch (e: any) {
    sendError.value = e.response?.data?.message ?? 'Erro ao enviar comando.'
  } finally { sending.value = false }
}

// ── History table ─────────────────────────────────────────────────────────────

const statusVariant = (s: string): any =>
  ({ PENDING: 'warning', COMPLETED: 'success', FAILED: 'danger', TIMEOUT: 'danger' }[s] ?? 'muted')

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const load = async () => {
  const r = await commandsApi.list(page.value)
  commands.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}

const deployableList = computed(() =>
  allFirmwares.value.filter(fw => fw.status !== 'DEPRECATED')
)

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Comandos">
      <template #actions>
        <AppButton v-if="authStore.isAdmin" size="sm" variant="primary" @click="openWizard">
          + Enviar Comando
        </AppButton>
      </template>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Tipo</th><th>Status</th><th>Dispositivo</th>
            <th>Enviado por</th><th>Enviado em</th><th>Concluído em</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in commands" :key="c.commandId">
            <td class="mono text-sm">{{ c.commandType }}</td>
            <td><AppBadge :variant="statusVariant(c.status)">{{ c.status }}</AppBadge></td>
            <td class="mono text-sm text-muted">{{ c.deviceId ?? '—' }}</td>
            <td class="text-sm">{{ c.createdByUsername ?? '—' }}</td>
            <td class="text-muted text-sm">{{ fmt(c.sentAt) }}</td>
            <td class="text-muted text-sm">{{ fmt(c.completedAt) }}</td>
          </tr>
          <tr v-if="!commands.length"><td colspan="6" class="empty">Nenhum comando encontrado</td></tr>
        </tbody>
      </table>

      <div class="pagination">
        <AppButton size="sm" variant="secondary" :disabled="page === 0" @click="page--; load()">Anterior</AppButton>
        <span class="text-muted text-sm">Página {{ page + 1 }} de {{ totalPages }}</span>
        <AppButton size="sm" variant="secondary" :disabled="page + 1 >= totalPages" @click="page++; load()">Próxima</AppButton>
      </div>
    </AppCard>

    <!-- ── Wizard ───────────────────────────────────────────────────────────── -->
    <div v-if="showWizard" class="modal-overlay" @click.self="showWizard = false">
      <div class="modal">

        <!-- Step: choose command -->
        <template v-if="wizardStep === 'command'">
          <h3 class="modal-title">Selecionar Comando</h3>
          <div class="cmd-grid">
            <button
              v-for="cmd in COMMANDS"
              :key="cmd.value"
              class="cmd-card"
              @click="pickCommand(cmd.value)"
            >
              <span class="cmd-label">{{ cmd.label }}</span>
              <span class="cmd-desc">{{ cmd.desc }}</span>
            </button>
          </div>
          <div class="modal-footer">
            <AppButton variant="ghost" @click="showWizard = false">Cancelar</AppButton>
          </div>
        </template>

        <!-- Step: choose firmware (UPDATE only) -->
        <template v-else-if="wizardStep === 'firmware'">
          <h3 class="modal-title">Selecionar Versão de Firmware</h3>
          <div v-if="loadingFirmwares" class="empty">Carregando firmwares...</div>
          <div v-else-if="!deployableList.length" class="empty">Nenhum firmware disponível para deploy.</div>
          <div v-else class="fw-list">
            <button
              v-for="fw in deployableList"
              :key="fw.firmwareId"
              class="fw-row"
              :class="{ selected: selectedFirmware?.firmwareId === fw.firmwareId }"
              @click="pickFirmware(fw)"
            >
              <span class="fw-version">v{{ fw.version }}</span>
              <AppBadge :variant="fw.status === 'DEPLOYED' ? 'success' : 'muted'" class="fw-badge">{{ fw.status }}</AppBadge>
              <span class="fw-date text-muted">{{ fw.uploadedAt ? new Date(fw.uploadedAt).toLocaleDateString('pt-BR') : '' }}</span>
            </button>
          </div>
          <div class="modal-footer">
            <AppButton variant="ghost" @click="wizardStep = 'command'">Voltar</AppButton>
          </div>
        </template>

        <!-- Step: choose devices -->
        <template v-else-if="wizardStep === 'devices'">
          <h3 class="modal-title">Selecionar Dispositivos</h3>
          <p v-if="selectedCommand === 'UPDATE' && selectedFirmware" class="step-hint">
            Firmware: <strong>v{{ selectedFirmware.version }}</strong>
          </p>
          <div v-if="loadingDevices" class="empty">Carregando dispositivos...</div>
          <div v-else-if="!allDevices.length" class="empty">Nenhum dispositivo registrado.</div>
          <div v-else class="device-grid">
            <button
              v-for="d in allDevices"
              :key="d.deviceId"
              class="device-chip"
              :class="{ selected: selectedDeviceIds.has(d.deviceId) }"
              @click="toggleDevice(d.deviceId)"
            >
              <span class="chip-name">{{ d.name }}</span>
              <span v-if="d.firmwareVersion" class="chip-ver">v{{ d.firmwareVersion }}</span>
              <span v-else class="chip-ver text-muted">—</span>
            </button>
          </div>
          <p v-if="sendError" class="field-error">{{ sendError }}</p>
          <div class="modal-footer">
            <span class="text-muted text-sm">{{ selectedDeviceIds.size }} selecionado(s)</span>
            <div style="display:flex;gap:8px">
              <AppButton variant="ghost" @click="selectedCommand === 'UPDATE' ? (wizardStep = 'firmware') : (wizardStep = 'command')">Voltar</AppButton>
              <AppButton variant="primary" :loading="sending && selectedCommand !== 'DEEP_SLEEP'" @click="finishDevices">
                {{ selectedCommand === 'DEEP_SLEEP' ? 'Próximo' : 'Enviar' }}
              </AppButton>
            </div>
          </div>
        </template>

        <!-- Step: params (DEEP_SLEEP) -->
        <template v-else-if="wizardStep === 'params'">
          <h3 class="modal-title">Parâmetros — DEEP_SLEEP</h3>
          <div class="form-group">
            <label>Duração do sleep <span class="text-muted">(segundos)</span></label>
            <input
              v-model.number="durationS"
              type="number"
              min="10"
              max="259200"
              class="field"
              placeholder="Ex: 3600"
            />
            <span class="field-hint">Mínimo 10s · Máximo 259200s (3 dias)</span>
          </div>
          <p v-if="sendError" class="field-error">{{ sendError }}</p>
          <div class="modal-footer">
            <AppButton variant="ghost" @click="wizardStep = 'devices'">Voltar</AppButton>
            <AppButton variant="primary" :loading="sending" @click="doSend">Enviar</AppButton>
          </div>
        </template>

        <!-- Step: result -->
        <template v-else-if="wizardStep === 'result' && sendResult">
          <h3 class="modal-title">Resultado — {{ sendResult.command }}</h3>
          <div class="result-section" v-if="sendResult.publishedTo.length">
            <p class="result-label success-label">Publicado ({{ sendResult.publishedTo.length }})</p>
            <p v-for="id in sendResult.publishedTo" :key="id" class="mono text-sm">{{ id }}</p>
          </div>
          <div class="result-section" v-if="sendResult.failed.length">
            <p class="result-label danger-label">Falhou no broker ({{ sendResult.failed.length }})</p>
            <p v-for="id in sendResult.failed" :key="id" class="mono text-sm text-muted">{{ id }}</p>
          </div>
          <div class="result-section" v-if="sendResult.skipped.length">
            <p class="result-label warn-label">Ignorado — inativo, inexistente ou comando pendente ({{ sendResult.skipped.length }})</p>
            <p v-for="id in sendResult.skipped" :key="id" class="mono text-sm text-muted">{{ id }}</p>
          </div>
          <div v-if="!sendResult.publishedTo.length && !sendResult.failed.length && !sendResult.skipped.length">
            <p class="text-muted text-sm">Nenhum device processado.</p>
          </div>
          <div class="modal-footer">
            <AppButton variant="primary" @click="showWizard = false">Fechar</AppButton>
          </div>
        </template>

      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
/* ── Table ───────────────────────────────────────────────────────────────── */
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.pagination { display: flex; align-items: center; justify-content: center; gap: var(--space-4); margin-top: var(--space-4); }

/* ── Modal shell ─────────────────────────────────────────────────────────── */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 520px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow: hidden; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; flex-shrink: 0; }
.modal-footer { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); margin-top: auto; flex-shrink: 0; padding-top: var(--space-2); border-top: 1px solid var(--border); }

/* ── Step: command grid ──────────────────────────────────────────────────── */
.cmd-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); overflow-y: auto; }
.cmd-card { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-4); cursor: pointer; text-align: left; display: flex; flex-direction: column; gap: var(--space-1); transition: border-color var(--transition), background var(--transition); }
.cmd-card:hover { border-color: var(--primary); background: var(--primary-dim); }
.cmd-label { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 600; color: var(--text); }
.cmd-desc { font-size: var(--text-xs); color: var(--text-muted); line-height: 1.4; }

/* ── Step: firmware list ─────────────────────────────────────────────────── */
.fw-list { display: flex; flex-direction: column; gap: var(--space-2); overflow-y: auto; max-height: 300px; }
.fw-row { display: flex; align-items: center; gap: var(--space-3); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-3) var(--space-4); cursor: pointer; text-align: left; transition: border-color var(--transition); }
.fw-row:hover { border-color: var(--primary); }
.fw-row.selected { border-color: var(--primary); background: var(--primary-dim); }
.fw-version { font-family: var(--font-mono); font-size: var(--text-sm); font-weight: 600; color: var(--text); flex: 1; }
.fw-badge { flex-shrink: 0; }
.fw-date { font-size: var(--text-xs); flex-shrink: 0; }

/* ── Step: device chips ──────────────────────────────────────────────────── */
.step-hint { font-size: var(--text-sm); color: var(--text-muted); margin: 0; flex-shrink: 0; }
.device-grid { display: flex; flex-wrap: wrap; gap: var(--space-2); overflow-y: auto; max-height: 300px; align-content: flex-start; }
.device-chip { display: flex; flex-direction: column; align-items: flex-start; gap: 2px; background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2) var(--space-3); cursor: pointer; transition: border-color var(--transition), background var(--transition); min-width: 120px; }
.device-chip:hover { border-color: var(--primary); }
.device-chip.selected { border-color: var(--primary); background: var(--primary-dim); }
.chip-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.chip-ver { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); }

/* ── Step: params ────────────────────────────────────────────────────────── */
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; }
.field:focus { border-color: var(--primary); }
.field-hint { font-size: var(--text-xs); color: var(--text-muted); }
.field-error { color: var(--danger); font-size: var(--text-sm); margin: 0; }

/* ── Step: result ────────────────────────────────────────────────────────── */
.result-section { display: flex; flex-direction: column; gap: var(--space-1); }
.result-label { font-size: var(--text-xs); font-weight: 600; text-transform: uppercase; letter-spacing: .5px; margin: 0; }
.success-label { color: var(--success); }
.warn-label { color: #f59e0b; }
.danger-label { color: var(--danger); }
</style>
