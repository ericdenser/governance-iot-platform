<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import ChipSelector from '@/components/ChipSelector.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { firmwareApi } from '@/services/firmware'
import { devicesApi } from '@/services/devices'
import { groupsApi } from '@/services/groups'
import { useAuthStore } from '@/stores/auth'
import { errorMessage, errorMessageFromBlob } from '@/utils/errors'
import { toast } from '@/composables/useToast'
import { confirm } from '@/composables/useConfirm'
import { absoluteTime } from '@/utils/format'
import { chipLabel, chipOf } from '@/utils/chips'
import { copyText } from '@/utils/clipboard'
import type {
  DeviceGroupResponseDTO,
  DeviceStatus,
  FirmwareResponseDTO,
  PendingDeviceDTO,
  TargetChip,
} from '@/types/models'

const authStore = useAuthStore()

const chip = ref<TargetChip>('ESP32_S3')
const firmwares = ref<FirmwareResponseDTO[]>([])
const groups = ref<DeviceGroupResponseDTO[]>([])
const pending = ref<PendingDeviceDTO[]>([])
const loading = ref(true)

const managedGroups = computed(() =>
  authStore.isAdmin ? groups.value : groups.value.filter(g => g.myRole === 'MEMBER' || g.myRole === 'OWNER')
)

const provisioningFirmware = computed(() =>
  firmwares.value.find(fw => fw.provisioningFirmware && chipOf(fw.targetChip) === chip.value) ?? null
)
const firmwareDeprecated = computed(() => provisioningFirmware.value?.latestVersion?.status === 'DEPRECATED')
const firmwareReady = computed(() => !!provisioningFirmware.value?.latestVersion && !firmwareDeprecated.value)

// Backend already limits the list to the caller's MEMBER/OWNER groups
const loadPending = async () => {
  const r = await devicesApi.listPending()
  pending.value = Array.isArray(r.data) ? r.data : []
}

// ── Form ─────────────────────────────────────────────────────────────────────
const emptyForm = () => ({ deviceName: '', wifiSsid: '', wifiPass: '', groupId: '' })
const form = ref(emptyForm())
const showPass = ref(false)
const generating = ref(false)
const formError = ref('')

// ── Device created by this page, followed until it activates ─────────────────
interface Created { deviceId: string; name: string; chip: TargetChip; filename: string }
const created = ref<Created | null>(null)
const createdStatus = ref<DeviceStatus | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

const stopPolling = () => { if (pollTimer) clearInterval(pollTimer); pollTimer = null }

const pollCreated = async () => {
  if (!created.value) return
  try {
    const r = await devicesApi.get(created.value.deviceId)
    createdStatus.value = r.data.status
    if (r.data.status === 'ACTIVE') {
      stopPolling()
      loadPending()
    }
  } catch {
    // Discarded or no longer visible
    stopPolling()
  }
}

const generate = async () => {
  const { deviceName, wifiSsid, wifiPass, groupId } = form.value
  if (!deviceName.trim() || !wifiSsid.trim() || !wifiPass.trim()) {
    formError.value = 'Nome, rede e senha do WiFi são obrigatórios.'
    return
  }
  if (!authStore.isAdmin && !groupId) {
    formError.value = 'Selecione o grupo do dispositivo.'
    return
  }
  generating.value = true
  formError.value = ''
  try {
    const payload: { deviceName: string; wifiSsid: string; wifiPass: string; groupId?: string; chip: TargetChip } = {
      deviceName: deviceName.trim(),
      wifiSsid: wifiSsid.trim(),
      wifiPass: wifiPass.trim(),
      chip: chip.value,
    }
    if (groupId) payload.groupId = groupId
    const r = await firmwareApi.generatePackage(payload)

    const filename = `flash_package_${payload.deviceName.replace(/[^a-zA-Z0-9_-]/g, '_')}.zip`
    const url = URL.createObjectURL(r.data)
    const a = document.createElement('a')
    a.href = url
    a.download = filename
    a.click()
    URL.revokeObjectURL(url)

    const deviceId = (r.headers['x-device-id'] as string | undefined) ?? ''
    created.value = { deviceId, name: payload.deviceName, chip: chip.value, filename }
    createdStatus.value = 'PENDING'
    form.value = emptyForm()
    loadPending()
    if (deviceId) {
      stopPolling()
      pollTimer = setInterval(pollCreated, 3000)
    }
  } catch (e: unknown) {
    formError.value = await errorMessageFromBlob(e, 'Erro ao gerar o pacote.')
  } finally {
    generating.value = false
  }
}

const startOver = () => {
  stopPolling()
  created.value = null
  createdStatus.value = null
}

// Switching chip leaves the follow-up; the device stays in the pending list
watch(chip, (next) => {
  if (created.value && created.value.chip !== next) startOver()
})

// ── Flash instructions per chip ──────────────────────────────────────────────
const flashCommand = computed(() => {
  if (!created.value) return ''
  if (created.value.chip === 'ESP8266') {
    return [
      'python3 -m esptool --chip esp8266 --port <PORTA> --baud 460800 write_flash \\',
      '  0x0      <firmware>.bin \\',
      '  0x200000 littlefs.bin',
    ].join('\n')
  }
  return [
    'python3 -m esptool --port <PORTA> --baud 460800 write_flash \\',
    '  0x0     bootloader.bin \\',
    '  0x8000  partition-table.bin \\',
    '  0x9000  nvs_device.bin \\',
    '  0xe000  ota_data_initial.bin \\',
    '  0x10000 <firmware>.bin',
  ].join('\n')
})

const copyCommand = async () => {
  if (await copyText(flashCommand.value)) toast.success('Comando copiado')
  else toast.error('Não foi possível copiar, selecione o texto e use Ctrl+C')
}

const activationText: Record<string, string> = {
  PENDING: 'Aguardando o primeiro boot do dispositivo',
  PROVISIONING: 'Registrando na plataforma',
  ACTIVE: 'Dispositivo ativo',
}

// ── Pending list ─────────────────────────────────────────────────────────────
// Token is single use and expires; an expired PENDING device can only be discarded
const isExpired = (d: PendingDeviceDTO) =>
  d.status === 'PENDING' && !!d.tokenExpiresAt && new Date(d.tokenExpiresAt).getTime() < Date.now()

const expiresText = (d: PendingDeviceDTO) => {
  if (!d.tokenExpiresAt) return '—'
  if (isExpired(d)) return 'expirado'
  const h = Math.round((new Date(d.tokenExpiresAt).getTime() - Date.now()) / 3_600_000)
  return h < 1 ? 'em menos de 1 h' : `em ${h} h`
}
const discarding = ref<string | null>(null)

const discard = async (d: PendingDeviceDTO) => {
  const ok = await confirm({
    title: `Descartar "${d.name}"?`,
    message: 'O dispositivo e o token de provisionamento serão removidos. O pacote gerado deixa de funcionar.',
    confirmText: 'Descartar',
    danger: true,
  })
  if (!ok) return
  discarding.value = d.deviceId
  try {
    await devicesApi.discardPending(d.deviceId)
    if (created.value?.deviceId === d.deviceId) startOver()
    await loadPending()
    toast.success(`"${d.name}" descartado`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao descartar.'))
  } finally {
    discarding.value = null
  }
}

onMounted(async () => {
  try {
    const [fw, grp] = await Promise.all([firmwareApi.list(), groupsApi.list(), loadPending()])
    firmwares.value = Array.isArray(fw.data) ? fw.data : []
    groups.value = Array.isArray(grp.data) ? grp.data : []
  } finally {
    loading.value = false
  }
})

onUnmounted(stopPolling)
</script>

<template>
  <AppLayout>
    <div class="toolbar">
      <ChipSelector v-model="chip" />
    </div>

    <div v-if="loading" class="empty">Carregando...</div>

    <div v-else class="grid">
      <!-- ── New device / follow-up ───────────────────────────────────── -->
      <section>
        <h3 class="block-title">{{ created ? `Dispositivo ${created.name}` : 'Novo dispositivo' }}</h3>
        <AppCard>
          <template v-if="!created">
            <p class="fw-line">
              <template v-if="firmwareReady && provisioningFirmware">
                Firmware de provisioning:
                <router-link :to="`/firmware/${provisioningFirmware.firmwareId}`" class="mono">
                  {{ provisioningFirmware.firmwareName }} v{{ provisioningFirmware.latestVersion?.version }}
                </router-link>
              </template>
              <span v-else-if="firmwareDeprecated" class="text-warning">
                A versão de provisioning de {{ chipLabel(chip) }} está deprecada. Publique uma versão nova em
                <router-link to="/firmware">Firmware</router-link>.
              </span>
              <span v-else class="text-warning">
                Nenhum firmware de provisioning para {{ chipLabel(chip) }}. Cadastre em
                <router-link to="/firmware">Firmware</router-link>.
              </span>
            </p>

            <form class="form" @submit.prevent="generate">
              <label class="field-label">Nome do dispositivo
                <input v-model="form.deviceName" class="field" maxlength="30" placeholder="ex: sensor-armazem-01" :disabled="!firmwareReady" />
              </label>
              <div class="row">
                <label class="field-label">Rede WiFi
                  <input v-model="form.wifiSsid" class="field" autocomplete="off" :disabled="!firmwareReady" />
                </label>
                <label class="field-label">Senha
                  <span class="pass-row">
                    <input v-model="form.wifiPass" :type="showPass ? 'text' : 'password'" class="field"
                           autocomplete="new-password" :disabled="!firmwareReady" />
                    <button type="button" class="pass-toggle" @click="showPass = !showPass">{{ showPass ? 'Ocultar' : 'Mostrar' }}</button>
                  </span>
                </label>
              </div>
              <label class="field-label">Grupo <span v-if="authStore.isAdmin" class="text-muted">(opcional)</span>
                <select v-model="form.groupId" class="field" :disabled="!firmwareReady">
                  <option value="">{{ authStore.isAdmin ? 'Nenhum' : 'Selecione' }}</option>
                  <option v-for="g in managedGroups" :key="g.groupId" :value="g.groupId">{{ g.name }}</option>
                </select>
              </label>

              <p v-if="formError" class="field-error">{{ formError }}</p>
              <div class="form-actions">
                <AppButton type="submit" variant="primary" :loading="generating" :disabled="!firmwareReady">
                  Gerar pacote
                </AppButton>
              </div>
            </form>
          </template>

          <!-- Steps after the package was generated -->
          <ol v-else class="steps">
            <li class="step done">
              <span class="step-title">Pacote baixado</span>
              <span class="mono text-muted">{{ created.filename }}</span>
            </li>
            <li class="step" :class="{ done: createdStatus !== 'PENDING' }">
              <span class="step-title">Gravar no {{ chipLabel(created.chip) }}</span>
              <span class="text-muted">Descompacte o ZIP e rode na pasta dele (o nome do firmware está no README):</span>
              <div class="command">
                <pre>{{ flashCommand }}</pre>
                <button type="button" class="copy-btn" @click="copyCommand">Copiar</button>
              </div>
            </li>
            <li class="step" :class="{ done: createdStatus === 'ACTIVE', current: createdStatus !== 'ACTIVE' }">
              <span class="step-title">Ativação</span>
              <span class="activation">
                <StatusLabel kind="device" :code="createdStatus ?? 'PENDING'" no-icon />
                <span class="text-muted">{{ activationText[createdStatus ?? 'PENDING'] }}</span>
              </span>
              <router-link v-if="createdStatus === 'ACTIVE'" :to="`/devices/${created.deviceId}`">Abrir dispositivo</router-link>
            </li>
          </ol>

          <div v-if="created" class="form-actions">
            <AppButton variant="secondary" @click="startOver">Provisionar outro</AppButton>
          </div>
        </AppCard>
      </section>

      <!-- ── Waiting for activation ───────────────────────────────────── -->
      <section>
        <h3 class="block-title">Aguardando ativação <span class="count">{{ pending.length }}</span></h3>
        <AppCard no-padding>
          <table class="tbl">
            <thead>
              <tr><th>Nome</th><th>Chip</th><th>Grupo</th><th>Criado por</th><th>Status</th><th>Expira</th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="d in pending" :key="d.deviceId">
                <td class="font-medium">{{ d.name }}</td>
                <td class="mono text-muted">{{ chipLabel(d.chip) }}</td>
                <td>{{ d.groupNames.length ? d.groupNames.join(', ') : '—' }}</td>
                <td :title="absoluteTime(d.createdAt)">{{ d.createdByUsername ?? '—' }}</td>
                <td><StatusLabel kind="device" :code="d.status" no-icon /></td>
                <td :class="isExpired(d) ? 'text-danger' : 'text-muted'" :title="absoluteTime(d.tokenExpiresAt)">
                  {{ d.status === 'PENDING' ? expiresText(d) : '—' }}
                </td>
                <td class="cell-action">
                  <AppButton v-if="d.status === 'PENDING'" size="sm" variant="danger-ghost"
                             :loading="discarding === d.deviceId" @click="discard(d)">Descartar</AppButton>
                </td>
              </tr>
              <tr v-if="!pending.length">
                <td colspan="7" class="empty-row">Nenhum dispositivo aguardando ativação</td>
              </tr>
            </tbody>
          </table>
        </AppCard>
      </section>
    </div>
  </AppLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; margin-bottom: var(--space-6); }

.grid { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 6fr); gap: var(--space-6); align-items: start; }

.block-title { display: flex; align-items: baseline; gap: var(--space-2); font-size: var(--text-sm); font-weight: 600; color: var(--text-secondary); margin: 0 0 var(--space-2); }
.count { font-weight: 400; color: var(--text-muted); font-family: var(--font-mono); font-size: var(--text-xs); }

.fw-line { font-size: var(--text-sm); color: var(--text-secondary); margin: 0 0 var(--space-5); }
.text-warning { color: var(--warning); }

.form { display: flex; flex-direction: column; gap: var(--space-4); }
.row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.field-label { display: flex; flex-direction: column; gap: var(--space-2); font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; width: 100%; box-sizing: border-box; }
.field:focus { border-color: var(--primary); }
.field:disabled { opacity: 0.5; }
.pass-row { display: flex; gap: var(--space-2); }
.pass-toggle { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 0 var(--space-3); font-size: var(--text-xs); color: var(--text-muted); cursor: pointer; }
.pass-toggle:hover { color: var(--text); }
.field-error { color: var(--danger); font-size: var(--text-sm); margin: 0; }
.form-actions { display: flex; justify-content: flex-end; margin-top: var(--space-4); }

.steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
.step {
  position: relative;
  display: flex; flex-direction: column; gap: var(--space-2);
  padding: 0 0 var(--space-5) var(--space-6);
  border-left: 1px solid var(--border);
  margin-left: 6px;
  font-size: var(--text-sm);
}
.step:last-child { padding-bottom: 0; border-left-color: transparent; }
.step::before {
  content: '';
  position: absolute; left: -6px; top: 3px;
  width: 11px; height: 11px; border-radius: 50%;
  background: var(--bg); border: 2px solid var(--border-strong);
}
.step.done::before { background: var(--success); border-color: var(--success); }
.step.current::before { border-color: var(--accent); animation: pulse 1.6s ease-in-out infinite; }
.step-title { font-weight: 500; color: var(--text); }
.activation { display: flex; align-items: center; gap: var(--space-2); }

.command { position: relative; }
.command pre {
  margin: 0; padding: var(--space-3) var(--space-4);
  background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md);
  font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-secondary);
  overflow-x: auto; white-space: pre;
  user-select: all;
}
.copy-btn {
  position: absolute; top: var(--space-2); right: var(--space-2);
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-sm);
  padding: 2px var(--space-2); font-size: var(--text-xs); color: var(--text-muted); cursor: pointer;
}
.copy-btn:hover { color: var(--text); }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: var(--space-3) var(--space-4); text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) var(--space-4); border-top: 1px solid var(--border); font-size: var(--text-sm); }
.cell-action { text-align: right; width: 1%; white-space: nowrap; }
.empty-row { color: var(--text-muted); }

.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; color: var(--text); }
.text-muted { color: var(--text-muted); }
.text-danger { color: var(--danger); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }

@keyframes pulse {
  0%, 100% { box-shadow: 0 0 0 0 var(--accent-dim); }
  50%      { box-shadow: 0 0 0 5px var(--accent-dim); }
}
@media (prefers-reduced-motion: reduce) {
  .step.current::before { animation: none; }
}

@media (max-width: 1100px) {
  .grid { grid-template-columns: 1fr; }
}
@media (max-width: 540px) {
  .row { grid-template-columns: 1fr; }
}
</style>
