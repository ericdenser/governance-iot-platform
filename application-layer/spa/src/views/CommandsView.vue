<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import AppPagination from '@/components/AppPagination.vue'
import { commandsApi } from '@/services/commands'
import { devicesApi } from '@/services/devices'
import { firmwareApi } from '@/services/firmware'
import type {
  CommandAggregateStatus,
  CommandBatchDTO,
  CommandRecordResponseDTO,
  CommandRequest,
  CommandStatus,
  DeviceCommands,
  DeviceSummaryDTO,
  DeployableVersionProjection,
} from '@/types/models'
import { errorMessage } from '@/utils/errors'
import { toast } from '@/composables/useToast'
import { confirm } from '@/composables/useConfirm'

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'

// ── State ──────────────────────────────────────────────────────────────────

const devices = ref<DeviceSummaryDTO[]>([])
const loadingDevices = ref(true)
const selectedDeviceIds = ref<Set<string>>(new Set())

const batches = ref<CommandBatchDTO[]>([])
const loadingBatches = ref(true)
const page = ref(0)
const totalPages = ref(1)

const expanded = ref<Set<string>>(new Set())
const recordsByBatch = ref<Record<string, CommandRecordResponseDTO[]>>({})
const loadingRecords = ref<Set<string>>(new Set())

const runningCommand = ref<DeviceCommands | null>(null)

// ── Static commands ────────────────────────────────────────────────────────

interface CommandDef {
  value: DeviceCommands
  label: string
  desc: string
  destructive?: boolean
  needsFirmware?: boolean
  needsDuration?: boolean
}

const COMMANDS: CommandDef[] = [
  { value: 'UPDATE',            label: 'Atualizar Firmware', desc: 'OTA — instala uma versão específica no device', needsFirmware: true },
  { value: 'REBOOT',            label: 'Reiniciar',           desc: 'Reinicia o ESP imediatamente' },
  { value: 'DEEP_SLEEP',        label: 'Deep Sleep',          desc: 'Modo de economia por N segundos', needsDuration: true },
  { value: 'FIRMWARE_ROLLBACK', label: 'Rollback Firmware',   desc: 'Reverte pra versão anterior no NVS', destructive: true },
]

// ── Filtered devices (ACTIVE only — reachable) ─────────────────────────────

const targetDevices = computed(() => devices.value.filter(d => d.status === 'ACTIVE'))

const selectedCount = computed(() => {
  let n = 0
  for (const d of targetDevices.value) if (selectedDeviceIds.value.has(d.deviceId)) n++
  return n
})

const toggleDevice = (id: string) => {
  const next = new Set(selectedDeviceIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selectedDeviceIds.value = next
}

const selectAll = () => {
  selectedDeviceIds.value = new Set(targetDevices.value.map(d => d.deviceId))
}

const clearSelection = () => {
  selectedDeviceIds.value = new Set()
}

// ── Firmware picker modal (UPDATE) ─────────────────────────────────────────

const showFirmwareModal = ref(false)
const firmwareList = ref<DeployableVersionProjection[]>([])
const loadingFirmwares = ref(false)
const pickedFirmware = ref<DeployableVersionProjection | null>(null)

const openFirmwareModal = async () => {
  pickedFirmware.value = null
  showFirmwareModal.value = true
  loadingFirmwares.value = true
  try {
    const r = await firmwareApi.listDeployable()
    firmwareList.value = Array.isArray(r.data) ? r.data : []
  } finally { loadingFirmwares.value = false }
}

const confirmFirmwareAndSend = async () => {
  if (!pickedFirmware.value) return
  showFirmwareModal.value = false
  await send('UPDATE', { versionId: pickedFirmware.value.versionId })
}

// filtra devices que ja tem a versao escolhida
const deviceHasVersion = (d: DeviceSummaryDTO) =>
  !!pickedFirmware.value && d.firmwareVersionId === pickedFirmware.value.versionId

// ── Deep sleep modal ───────────────────────────────────────────────────────

const showDeepSleepModal = ref(false)
const durationS = ref(300)
const durationError = ref('')

const openDeepSleepModal = () => {
  durationS.value = 300
  durationError.value = ''
  showDeepSleepModal.value = true
}

const confirmDeepSleepAndSend = async () => {
  const v = Number(durationS.value)
  if (!Number.isFinite(v) || v < 10 || v > 259200) {
    durationError.value = 'Duração deve estar entre 10 e 259200 segundos (3 dias).'
    return
  }
  showDeepSleepModal.value = false
  await send('DEEP_SLEEP', { duration_s: v })
}

// ── Command dispatch ───────────────────────────────────────────────────────

const onCommandClick = async (cmd: CommandDef) => {
  if (selectedCount.value === 0) {
    toast.error('Selecione ao menos um device.')
    return
  }
  if (runningCommand.value) return

  if (cmd.needsFirmware) return openFirmwareModal()
  if (cmd.needsDuration) return openDeepSleepModal()

  if (cmd.destructive) {
    const ok = await confirm({
      title: 'Rollback de firmware',
      message: `Reverte ${selectedCount.value} device(s) para a versão anterior no NVS. Ação irreversível.`,
      confirmText: 'Reverter',
    })
    if (!ok) return
  } else {
    const ok = await confirm({
      title: 'Reiniciar devices',
      message: `Reinicia ${selectedCount.value} device(s) agora.`,
      confirmText: 'Reiniciar',
    })
    if (!ok) return
  }

  await send(cmd.value)
}

const send = async (command: DeviceCommands, params?: Record<string, unknown>) => {
  runningCommand.value = command
  try {
    const targetIds = [...selectedDeviceIds.value].filter(id =>
      targetDevices.value.some(d => d.deviceId === id)
    )
    if (!targetIds.length) {
      toast.error('Nenhum device válido selecionado.')
      return
    }
    const payload: CommandRequest = { command, targetDevices: targetIds }
    if (params) payload.params = params
    const r = await commandsApi.send(payload)
    const { publishedTo, failed, skipped } = r.data
    const parts: string[] = []
    if (publishedTo.length) parts.push(`${publishedTo.length} publicado(s)`)
    if (failed.length) parts.push(`${failed.length} falhou no broker`)
    if (skipped.length) parts.push(`${skipped.length} ignorado(s)`)
    if (publishedTo.length && !failed.length) {
      toast.success(`${command}: ${parts.join(' · ')}`)
    } else if (!publishedTo.length && (failed.length || skipped.length)) {
      toast.error(`${command}: ${parts.join(' · ') || 'nenhum device processado'}`)
    } else {
      toast.info(`${command}: ${parts.join(' · ')}`)
    }
    page.value = 0
    await loadBatches()
    clearSelection()
  } catch (e: unknown) {
    toast.error(errorMessage(e, `Erro ao enviar ${command}.`))
  } finally { runningCommand.value = null }
}

// ── History (batches) ──────────────────────────────────────────────────────

const aggregateVariant = (s: CommandAggregateStatus): BadgeVariant =>
  (({ IN_PROGRESS: 'warning', SUCCESS: 'success', PARTIAL: 'warning', FAILED: 'danger' } as Record<CommandAggregateStatus, BadgeVariant>)[s] ?? 'muted')

const AGGREGATE_LABEL: Record<CommandAggregateStatus, string> = {
  IN_PROGRESS: 'EM ANDAMENTO',
  SUCCESS: 'SUCESSO',
  PARTIAL: 'PARCIAL',
  FAILED: 'FALHOU',
}

const statusVariant = (s: CommandStatus): BadgeVariant =>
  (({
    PENDING: 'warning',
    COMPLETED_SUCCESS: 'success',
    FAILED: 'danger',
    TIMEOUT: 'danger',
    PUBLISH_FAILED: 'danger',
    SKIPPED: 'muted',
  } as Record<CommandStatus, BadgeVariant>)[s] ?? 'muted')

const countsSummary = (b: CommandBatchDTO) => {
  const parts: string[] = []
  if (b.success) parts.push(`${b.success} ok`)
  if (b.pending) parts.push(`${b.pending} pendente(s)`)
  if (b.failed) parts.push(`${b.failed} falhou`)
  if (b.skipped) parts.push(`${b.skipped} ignorado(s)`)
  if (b.notFound) parts.push(`${b.notFound} não encontrado(s)`)
  return parts.join(' · ') || '—'
}

const fmtTime = (iso: string | null) => iso ? new Date(iso).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit', second: '2-digit' }) : '—'
const fmtDate = (iso: string | null) => iso ? new Date(iso).toLocaleDateString('pt-BR') : '—'
const fmtFull = (iso: string | null) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const toggleExpand = async (batchId: string) => {
  const next = new Set(expanded.value)
  if (next.has(batchId)) {
    next.delete(batchId)
    expanded.value = next
    return
  }
  next.add(batchId)
  expanded.value = next
  if (!recordsByBatch.value[batchId]) {
    loadingRecords.value = new Set(loadingRecords.value).add(batchId)
    try {
      const r = await commandsApi.records(batchId)
      recordsByBatch.value = { ...recordsByBatch.value, [batchId]: r.data ?? [] }
    } catch {
      recordsByBatch.value = { ...recordsByBatch.value, [batchId]: [] }
    } finally {
      const ld = new Set(loadingRecords.value)
      ld.delete(batchId)
      loadingRecords.value = ld
    }
  }
}

// ── Loaders ────────────────────────────────────────────────────────────────

const loadDevices = async () => {
  loadingDevices.value = true
  try {
    devices.value = await devicesApi.listAll()
  } finally { loadingDevices.value = false }
}

const loadBatches = async () => {
  loadingBatches.value = true
  try {
    const r = await commandsApi.list(page.value)
    batches.value = r.data.content ?? []
    totalPages.value = r.data.page?.totalPages ?? 1
    expanded.value = new Set()
    recordsByBatch.value = {}
  } finally { loadingBatches.value = false }
}

const changePage = async (p: number) => { page.value = p; await loadBatches() }

onMounted(async () => { await Promise.all([loadDevices(), loadBatches()]) })
</script>

<template>
  <AppLayout>
    <div class="cmd-grid">
      <!-- ── Target Devices ───────────────────────────────────────────── -->
      <AppCard title="Devices Alvo">
        <template #actions>
          <div class="target-actions">
            <button v-if="targetDevices.length && selectedCount < targetDevices.length"
              class="mini-btn" @click="selectAll">Todos</button>
            <button v-if="selectedCount" class="mini-btn" @click="clearSelection">Limpar</button>
          </div>
        </template>

        <p class="panel-hint mono">
          {{ targetDevices.length }} ACTIVE
          <span v-if="selectedCount" class="selected-count"> · {{ selectedCount }} selecionado(s)</span>
        </p>

        <div v-if="loadingDevices" class="empty">Carregando devices...</div>
        <div v-else-if="!targetDevices.length" class="empty">Nenhum device ACTIVE disponível</div>
        <div v-else class="device-list">
          <label v-for="d in targetDevices" :key="d.deviceId"
            class="device-row"
            :class="{ selected: selectedDeviceIds.has(d.deviceId) }">
            <input type="checkbox"
              :checked="selectedDeviceIds.has(d.deviceId)"
              @change="toggleDevice(d.deviceId)" />
            <div class="dev-body">
              <span class="dev-name">{{ d.name }}</span>
              <span class="dev-meta mono">{{ d.firmwareVersion ? 'v' + d.firmwareVersion : '—' }}</span>
            </div>
            <span class="status-dot ok"></span>
          </label>
        </div>
      </AppCard>

      <!-- ── Available Commands ───────────────────────────────────────── -->
      <AppCard title="Comandos Disponíveis">
        <p class="panel-hint mono">Clique pra disparar nos selecionados</p>

        <div class="cmd-list">
          <button v-for="cmd in COMMANDS" :key="cmd.value"
            class="cmd-card"
            :class="{ destructive: cmd.destructive, running: runningCommand === cmd.value }"
            :disabled="selectedCount === 0 || runningCommand !== null"
            @click="onCommandClick(cmd)">
            <span class="cmd-icon-slot" :class="{ destructive: cmd.destructive }">
              <!-- Icon per command -->
              <svg v-if="cmd.value === 'UPDATE'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="21 8 21 21 3 21 3 8"/><rect x="1" y="3" width="22" height="5"/><line x1="10" y1="12" x2="14" y2="12"/></svg>
              <svg v-else-if="cmd.value === 'REBOOT'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :class="{ spin: runningCommand === cmd.value }"><polyline points="23 4 23 10 17 10"/><polyline points="1 20 1 14 7 14"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/></svg>
              <svg v-else-if="cmd.value === 'DEEP_SLEEP'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"/></svg>
              <svg v-else-if="cmd.value === 'FIRMWARE_ROLLBACK'" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"/><path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10"/></svg>
            </span>
            <div class="cmd-body">
              <span class="cmd-label">{{ cmd.label }}</span>
              <span class="cmd-desc mono">{{ cmd.desc }}</span>
            </div>
          </button>
        </div>
      </AppCard>

      <!-- ── Dispatch History ─────────────────────────────────────────── -->
      <AppCard title="Histórico">
        <p class="panel-hint mono">Últimos comandos · clique pra expandir</p>

        <div v-if="loadingBatches" class="empty">Carregando histórico...</div>
        <div v-else-if="!batches.length" class="empty">Nenhum comando enviado</div>
        <div v-else class="hist-list">
          <div v-for="b in batches" :key="b.batchId" class="hist-card">
            <button class="hist-header" @click="toggleExpand(b.batchId)">
              <div class="hist-top">
                <span class="hist-cmd mono">
                  {{ b.commandType }}<span v-if="b.targetVersionLabel" class="text-muted"> · v{{ b.targetVersionLabel }}</span>
                </span>
                <AppBadge :variant="aggregateVariant(b.aggregateStatus)">
                  {{ AGGREGATE_LABEL[b.aggregateStatus] ?? b.aggregateStatus }}
                </AppBadge>
              </div>
              <div class="hist-meta mono">
                <span>{{ b.total }} device(s)</span>
                <span class="sep">·</span>
                <span class="counts">{{ countsSummary(b) }}</span>
              </div>
              <div class="hist-footer mono">
                <span>{{ b.createdByUsername ?? '—' }}</span>
                <span class="sep">·</span>
                <span :title="fmtFull(b.sentAt)">{{ fmtDate(b.sentAt) }} {{ fmtTime(b.sentAt) }}</span>
                <span class="chevron" :class="{ open: expanded.has(b.batchId) }">›</span>
              </div>
            </button>

            <div v-if="expanded.has(b.batchId)" class="hist-detail">
              <div v-if="loadingRecords.has(b.batchId)" class="empty">Carregando...</div>
              <div v-else-if="!(recordsByBatch[b.batchId] ?? []).length" class="empty">Nenhum device visível</div>
              <div v-else class="record-list">
                <div v-for="c in recordsByBatch[b.batchId]" :key="c.commandId" class="record-row">
                  <span class="rec-name">{{ c.deviceName ?? c.deviceId ?? '—' }}</span>
                  <AppBadge :variant="statusVariant(c.status)" class="rec-status">{{ c.status }}</AppBadge>
                  <span v-if="c.errorMessage" class="rec-reason mono">{{ c.errorMessage }}</span>
                  <span v-else class="rec-reason mono text-muted">—</span>
                  <span class="rec-time mono">{{ fmtTime(c.completedAt) }}</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        <AppPagination :page="page" :total-pages="totalPages" @change="changePage" />
      </AppCard>
    </div>

    <!-- ── UPDATE: firmware picker modal ────────────────────────────────── -->
    <div v-if="showFirmwareModal" class="modal-overlay" @click.self="showFirmwareModal = false">
      <div class="modal">
        <h3 class="modal-title">Escolher Firmware — UPDATE</h3>
        <p class="modal-hint mono">{{ selectedCount }} device(s) alvo</p>

        <div v-if="loadingFirmwares" class="empty">Carregando firmwares...</div>
        <div v-else-if="!firmwareList.length" class="empty">Nenhum firmware disponível</div>
        <div v-else class="fw-list">
          <button v-for="fw in firmwareList" :key="fw.versionId"
            class="fw-row"
            :class="{ selected: pickedFirmware?.versionId === fw.versionId }"
            @click="pickedFirmware = fw">
            <span class="fw-name">{{ fw.firmwareName }}</span>
            <span class="fw-version mono">v{{ fw.version }}</span>
            <AppBadge :variant="fw.status === 'DEPLOYED' ? 'success' : 'muted'">{{ fw.status }}</AppBadge>
          </button>
        </div>

        <p v-if="pickedFirmware" class="modal-hint mono warn">
          {{ targetDevices.filter(d => selectedDeviceIds.has(d.deviceId) && deviceHasVersion(d)).length }} device(s) selecionados já rodam essa versão — serão ignorados pelo backend.
        </p>

        <div class="modal-footer">
          <AppButton variant="ghost" @click="showFirmwareModal = false">Cancelar</AppButton>
          <AppButton variant="primary" :disabled="!pickedFirmware" @click="confirmFirmwareAndSend">Enviar UPDATE</AppButton>
        </div>
      </div>
    </div>

    <!-- ── DEEP_SLEEP: duration modal ───────────────────────────────────── -->
    <div v-if="showDeepSleepModal" class="modal-overlay" @click.self="showDeepSleepModal = false">
      <div class="modal">
        <h3 class="modal-title">Duração — DEEP_SLEEP</h3>
        <p class="modal-hint mono">{{ selectedCount }} device(s) alvo</p>

        <div class="form-group">
          <label>Duração <span class="text-muted">(segundos)</span></label>
          <input type="number" min="10" max="259200" class="field" v-model.number="durationS" placeholder="ex: 300" />
          <span class="field-hint mono">Mínimo 10s · Máximo 259200s (3 dias)</span>
        </div>
        <p v-if="durationError" class="field-error">{{ durationError }}</p>

        <div class="modal-footer">
          <AppButton variant="ghost" @click="showDeepSleepModal = false">Cancelar</AppButton>
          <AppButton variant="primary" @click="confirmDeepSleepAndSend">Enviar DEEP_SLEEP</AppButton>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
/* ── Grid ─────────────────────────────────────────────────────────────── */
.cmd-grid {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(280px, 1fr) minmax(320px, 1.4fr);
  gap: var(--space-4);
  align-items: start;
}
@media (max-width: 1200px) {
  .cmd-grid { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 768px) {
  .cmd-grid { grid-template-columns: 1fr; }
}

.panel-hint { font-size: var(--text-xs); color: var(--text-muted); margin: 0 0 var(--space-3) 0; letter-spacing: .3px; }
.panel-hint .selected-count { color: var(--primary); }

.target-actions { display: flex; gap: var(--space-1); }
.mini-btn { background: none; border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 2px 8px; font-size: var(--text-xs); color: var(--text-muted); cursor: pointer; font-family: var(--font-mono); transition: border-color var(--transition), color var(--transition); }
.mini-btn:hover { border-color: var(--primary); color: var(--primary); }

.empty { text-align: center; color: var(--text-muted); padding: var(--space-6) 0; font-size: var(--text-sm); }
.mono { font-family: var(--font-mono); }
.text-muted { color: var(--text-muted); }

/* ── Target devices ───────────────────────────────────────────────────── */
.device-list { display: flex; flex-direction: column; gap: 4px; max-height: 480px; overflow-y: auto; }
.device-row { display: flex; align-items: center; gap: var(--space-2); padding: var(--space-2) var(--space-3); border-radius: var(--radius-sm); cursor: pointer; border: 1px solid transparent; transition: background var(--transition), border-color var(--transition); }
.device-row:hover { background: var(--panel); }
.device-row.selected { background: var(--panel); border-color: var(--primary); }
.device-row input[type="checkbox"] { accent-color: var(--primary); width: 14px; height: 14px; margin: 0; }
.dev-body { display: flex; flex-direction: column; gap: 2px; flex: 1; min-width: 0; }
.dev-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.dev-meta { font-size: var(--text-xs); color: var(--text-muted); }
.status-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.status-dot.ok { background: var(--success); }

/* ── Commands list ────────────────────────────────────────────────────── */
.cmd-list { display: flex; flex-direction: column; gap: var(--space-2); }
.cmd-card { display: flex; align-items: flex-start; gap: var(--space-3); padding: var(--space-3); background: none; border: 1px solid var(--border); border-radius: var(--radius-md); cursor: pointer; text-align: left; transition: border-color var(--transition), background var(--transition); }
.cmd-card:hover:not(:disabled) { border-color: var(--primary); background: var(--panel); }
.cmd-card:disabled { opacity: 0.35; cursor: not-allowed; }
.cmd-card.destructive:hover:not(:disabled) { border-color: var(--danger); background: rgba(239, 68, 68, 0.05); }
.cmd-card.running { opacity: 0.7; }

.cmd-icon-slot { display: inline-flex; align-items: center; justify-content: center; width: 30px; height: 30px; border-radius: var(--radius-sm); background: rgba(6, 182, 212, 0.1); color: var(--primary); flex-shrink: 0; }
.cmd-icon-slot.destructive { background: rgba(239, 68, 68, 0.1); color: var(--danger); }

.cmd-body { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.cmd-label { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.cmd-desc { font-size: var(--text-xs); color: var(--text-muted); line-height: 1.35; }

@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.spin { animation: spin 1s linear infinite; }

/* ── History cards ────────────────────────────────────────────────────── */
.hist-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 520px; overflow-y: auto; }
.hist-card { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); overflow: hidden; }

.hist-header { width: 100%; display: flex; flex-direction: column; gap: 6px; padding: var(--space-3); background: none; border: none; cursor: pointer; text-align: left; color: inherit; }
.hist-header:hover { background: rgba(255, 255, 255, 0.02); }

.hist-top { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
.hist-cmd { font-size: var(--text-sm); font-weight: 500; color: var(--text); }

.hist-meta { font-size: var(--text-xs); color: var(--text-muted); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.hist-meta .counts { color: var(--text-secondary); }
.sep { color: var(--text-muted); opacity: 0.5; }

.hist-footer { display: flex; align-items: center; gap: 4px; font-size: var(--text-xs); color: var(--text-muted); }
.hist-footer .chevron { margin-left: auto; font-size: var(--text-md); transition: transform var(--transition); }
.hist-footer .chevron.open { transform: rotate(90deg); }

.hist-detail { border-top: 1px solid var(--border); padding: var(--space-2) var(--space-3); background: var(--surface); }
.record-list { display: flex; flex-direction: column; gap: 4px; }
.record-row { display: grid; grid-template-columns: minmax(0, 1.2fr) auto minmax(0, 2fr) auto; gap: var(--space-2); align-items: center; padding: 4px 0; font-size: var(--text-xs); border-bottom: 1px solid var(--border); }
.record-row:last-child { border-bottom: none; }
.rec-name { font-family: var(--font-sans); color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rec-status { justify-self: start; }
.rec-reason { color: var(--text-secondary); overflow-wrap: anywhere; word-break: break-word; line-height: 1.4; }
.rec-time { color: var(--text-muted); text-align: right; }

/* ── Modals ───────────────────────────────────────────────────────────── */
.modal-overlay { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 520px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow: hidden; }
.modal-title { font-family: var(--font-sans); font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-hint { font-size: var(--text-xs); color: var(--text-muted); margin: 0; }
.modal-hint.warn { color: var(--warning, #f59e0b); }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }

.fw-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 320px; overflow-y: auto; }
.fw-row { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); cursor: pointer; text-align: left; transition: border-color var(--transition); color: inherit; }
.fw-row:hover { border-color: var(--primary); }
.fw-row.selected { border-color: var(--primary); background: rgba(6, 182, 212, 0.06); }
.fw-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fw-version { font-size: var(--text-sm); color: var(--text-muted); }

.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; font-family: var(--font-mono); }
.field:focus { border-color: var(--primary); }
.field-hint { font-size: var(--text-xs); color: var(--text-muted); }
.field-error { color: var(--danger); font-size: var(--text-sm); margin: 0; }
</style>
