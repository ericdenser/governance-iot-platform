<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import ChipSelector from '@/components/ChipSelector.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { firmwareApi } from '@/services/firmware'
import { sensorsApi } from '@/services/sensors'
import { groupsApi } from '@/services/groups'
import { useAuthStore } from '@/stores/auth'
import type {
  FirmwareResponseDTO,
  DeviceGroupResponseDTO,
  SensorResponseDTO,
  CreateFirmwareRequest,
  TargetChip,
} from '@/types/models'
import { errorMessage } from '@/utils/errors'
import { toast } from '@/composables/useToast'
import { absoluteTime } from '@/utils/format'
import { chipLabel, chipOf } from '@/utils/chips'

const authStore = useAuthStore()
const router = useRouter()

const firmwares = ref<FirmwareResponseDTO[]>([])
const groups = ref<DeviceGroupResponseDTO[]>([])
const loading = ref(true)


// Groups where user can upload firmware (MEMBER/OWNER)
const managedGroupIds = computed(() =>
  new Set(
    groups.value
      .filter(g => g.myRole === 'MEMBER' || g.myRole === 'OWNER')
      .map(g => g.groupId)
  )
)

const groupName = (ownerGroupId: string | null) => {
  if (!ownerGroupId) return 'Plataforma'
  return groups.value.find(g => g.groupId === ownerGroupId)?.name ?? ownerGroupId
}

const canCreate = computed(() => authStore.isAdmin || managedGroupIds.value.size > 0)

// Lista, novo firmware e pacote de flash valem para o chip selecionado
const selectedChip = ref<TargetChip>('ESP32_S3')
const chipFirmwares = computed(() =>
  firmwares.value.filter(fw => chipOf(fw.targetChip) === selectedChip.value)
)

const operationalFirmwares = computed(() =>
  chipFirmwares.value.filter(fw => !fw.provisioningFirmware)
)

// Last activity: newest version upload, or creation when there is no version yet
const updatedAt = (fw: FirmwareResponseDTO) => fw.latestVersion?.uploadedAt ?? fw.createdAt
const fmtDate = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleDateString('pt-BR') : '—'

const provisioningFirmware = computed(() =>
  chipFirmwares.value.find(fw => fw.provisioningFirmware) ?? null
)

const load = async () => {
  const r = await firmwareApi.list()
  firmwares.value = Array.isArray(r.data) ? r.data : []
}

// ── Create Firmware modal ─────────────────────────────────────────────────────
const showCreate = ref(false)
const createFile = ref<File | null>(null)
const createMeta = ref({
  firmwareName: '',
  description: '',
  initialVersion: '',
  isProvisioning: false,
  ownerGroupId: '',
  deepSleepEnabled: false,
  deepSleepIntervalS: 300,
})
const creating = ref(false)
const createError = ref('')
const availableSensors = ref<SensorResponseDTO[]>([])
const selectedSensors = ref<Map<string, number>>(new Map())

const uploadableGroups = computed(() => {
  if (authStore.isAdmin) return groups.value
  return groups.value.filter(g => g.myRole === 'MEMBER' || g.myRole === 'OWNER')
})

const openCreate = async (asProvisioning = false) => {
  createFile.value = null
  createMeta.value = {
    firmwareName: '',
    description: '',
    initialVersion: '',
    isProvisioning: asProvisioning,
    ownerGroupId: '',
    deepSleepEnabled: false,
    deepSleepIntervalS: 300,
  }
  createError.value = ''
  selectedSensors.value = new Map()
  showCreate.value = true
  const r = await sensorsApi.list()
  availableSensors.value = Array.isArray(r.data) ? r.data : []
}

const toggleSensor = (sensorId: string) => {
  const next = new Map(selectedSensors.value)
  if (next.has(sensorId)) next.delete(sensorId); else next.set(sensorId, 0)
  selectedSensors.value = next
}

const setSensorPin = (sensorId: string, pin: number) => {
  const next = new Map(selectedSensors.value)
  next.set(sensorId, pin)
  selectedSensors.value = next
}

const doCreate = async () => {
  const meta = createMeta.value
  if (!createFile.value || !meta.firmwareName.trim() || !meta.initialVersion.trim()) {
    createError.value = 'Arquivo, nome do firmware e versão inicial são obrigatórios.'
    return
  }
  if (!authStore.isAdmin && !meta.isProvisioning && !meta.ownerGroupId) {
    createError.value = 'Selecione o grupo ao qual o firmware pertence.'
    return
  }
  if (meta.deepSleepEnabled && (!meta.deepSleepIntervalS || meta.deepSleepIntervalS < 30)) {
    createError.value = 'Intervalo de deep sleep deve ser >= 30 segundos.'
    return
  }
  creating.value = true; createError.value = ''
  try {
    const sensors = [...selectedSensors.value.entries()].map(([sensorId, pin]) => ({ sensorId, pin }))
    const payload: CreateFirmwareRequest = {
      firmwareName: meta.firmwareName.trim(),
      description: meta.description.trim() || null,
      initialVersion: meta.initialVersion.trim(),
      isProvisioning: meta.isProvisioning,
      deepSleepEnabled: meta.deepSleepEnabled,
      deepSleepIntervalS: meta.deepSleepEnabled ? meta.deepSleepIntervalS : null,
      sensors,
      targetChip: selectedChip.value,
    }
    if (!meta.isProvisioning && meta.ownerGroupId) {
      payload.ownerGroupId = meta.ownerGroupId
    }
    await firmwareApi.create(createFile.value, payload as unknown as Record<string, unknown>)
    showCreate.value = false
    await load()
    toast.success(`Firmware "${payload.firmwareName}" criado (v${payload.initialVersion})`)
  } catch (e: unknown) {
    createError.value = errorMessage(e, 'Erro ao criar firmware.')
  } finally { creating.value = false }
}

// ── Navigation ────────────────────────────────────────────────────────────────
const goToDetail = (firmwareId: string) => router.push(`/firmware/${firmwareId}`)

onMounted(async () => {
  try {
    const [fwRes, grpRes] = await Promise.all([firmwareApi.list(), groupsApi.list()])
    firmwares.value = Array.isArray(fwRes.data) ? fwRes.data : []
    groups.value = Array.isArray(grpRes.data) ? grpRes.data : []
  } finally { loading.value = false }
})
</script>

<template>
  <AppLayout>
    <div class="toolbar">
      <ChipSelector v-model="selectedChip" />
      <div v-if="canCreate" class="toolbar-actions">
        <AppButton variant="primary" @click="openCreate(false)">Novo firmware</AppButton>
      </div>
    </div>

    <div v-if="loading" class="empty">Carregando...</div>
    <template v-else>
      <!-- Only one provisioning firmware per chip: a single line instead of a table -->
      <section class="block">
        <h3 class="block-title">Provisioning</h3>
        <button v-if="provisioningFirmware" type="button" class="prov-line" @click="goToDetail(provisioningFirmware.firmwareId)">
          <span class="font-medium">{{ provisioningFirmware.firmwareName }}</span>
          <span v-if="provisioningFirmware.latestVersion" class="mono">v{{ provisioningFirmware.latestVersion.version }}</span>
          <StatusLabel v-if="provisioningFirmware.latestVersion" kind="firmware" :code="provisioningFirmware.latestVersion.status" no-icon />
          <span class="text-muted" :title="absoluteTime(updatedAt(provisioningFirmware))">atualizado em {{ fmtDate(updatedAt(provisioningFirmware)) }}</span>
          <span class="chevron">→</span>
        </button>
        <p v-else class="prov-empty">
          Nenhum firmware de provisioning para {{ chipLabel(selectedChip) }}.
          <button v-if="authStore.isAdmin" type="button" class="link-btn" @click="openCreate(true)">Cadastrar</button>
        </p>
      </section>

      <section class="block">
        <h3 class="block-title">Operational <span class="count">{{ operationalFirmwares.length }}</span></h3>
        <AppCard no-padding>
          <table class="tbl">
            <thead>
              <tr>
                <th>Nome</th>
                <th>Última versão</th>
                <th>Versões</th>
                <th>Grupo</th>
                <th>Atualizado</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="fw in operationalFirmwares" :key="fw.firmwareId" class="tbl-row" @click="goToDetail(fw.firmwareId)">
                <td class="font-medium">{{ fw.firmwareName }}</td>
                <td>
                  <template v-if="fw.latestVersion">
                    <span class="mono">v{{ fw.latestVersion.version }}</span>
                    <StatusLabel kind="firmware" :code="fw.latestVersion.status" no-icon class="ver-status" />
                  </template>
                  <span v-else class="text-muted">—</span>
                </td>
                <td class="text-muted">{{ fw.versionsCount }}</td>
                <td>
                  <span v-if="!fw.ownerGroupId" class="text-muted">Plataforma</span>
                  <span v-else>{{ groupName(fw.ownerGroupId) }}</span>
                </td>
                <td class="text-muted" :title="absoluteTime(updatedAt(fw))">{{ fmtDate(updatedAt(fw)) }}</td>
              </tr>
              <tr v-if="!operationalFirmwares.length">
                <td colspan="5" class="empty-row">Nenhum firmware operational para {{ chipLabel(selectedChip) }}</td>
              </tr>
            </tbody>
          </table>
        </AppCard>
      </section>
    </template>

    <!-- ── Create Firmware modal ────────────────────────────────────────────── -->
    <div v-if="showCreate" class="modal-overlay" @click.self="showCreate = false">
      <div class="modal">
        <h3 class="modal-title">Novo Firmware <span class="title-chip mono">{{ chipLabel(selectedChip) }}</span></h3>

        <div class="form-group">
          <label>Arquivo .bin</label>
          <input type="file" accept=".bin" @change="e => createFile = (e.target as HTMLInputElement).files?.[0] ?? null" />
        </div>

        <div class="form-group">
          <label>Nome do firmware</label>
          <input v-model="createMeta.firmwareName" class="field" placeholder="ex: sensor-armazem-frio" />
        </div>

        <div class="form-group">
          <label>Descrição <span class="text-muted">(opcional)</span></label>
          <input v-model="createMeta.description" class="field" placeholder="Breve descrição do firmware" />
        </div>

        <div class="form-group">
          <label>Versão inicial</label>
          <input v-model="createMeta.initialVersion" class="field" placeholder="ex: 1.0.0" />
        </div>

        <div v-if="!createMeta.isProvisioning" class="form-group">
          <label>Grupo <span v-if="!authStore.isAdmin" class="text-danger">*</span><span v-else class="text-muted"> (opcional — vazio = plataforma)</span></label>
          <select v-model="createMeta.ownerGroupId" class="field">
            <option v-if="authStore.isAdmin" value="">Plataforma (sem grupo)</option>
            <option v-else value="" disabled>Selecione um grupo</option>
            <option v-for="g in uploadableGroups" :key="g.groupId" :value="g.groupId">{{ g.name }}</option>
          </select>
        </div>

        <label v-if="authStore.isAdmin" class="checkbox-row">
          <input type="checkbox" v-model="createMeta.isProvisioning" />
          Firmware de provisionamento (usado no flash inicial dos devices)
        </label>

        <label class="checkbox-row">
          <input type="checkbox" v-model="createMeta.deepSleepEnabled" />
          Firmware usa deep sleep
        </label>

        <div v-if="createMeta.deepSleepEnabled" class="form-group">
          <label>Intervalo de deep sleep (segundos)</label>
          <input
            type="number"
            min="30"
            v-model.number="createMeta.deepSleepIntervalS"
            class="field"
            placeholder="ex: 300"
          />
          <small class="text-muted">
            Usado pra calcular timeout de comandos (default: intervalo × 2 + 60s de margem).
          </small>
        </div>

        <div v-if="availableSensors.length" class="form-group">
          <label>Sensores <span class="text-muted">(opcional)</span></label>
          <div class="sensor-select-list">
            <div v-for="s in availableSensors" :key="s.sensorId" class="sensor-select-row">
              <label class="checkbox-row" style="flex:1;margin:0">
                <input type="checkbox"
                  :checked="selectedSensors.has(s.sensorId)"
                  @change="toggleSensor(s.sensorId)" />
                {{ s.name }}
              </label>
              <div v-if="selectedSensors.has(s.sensorId)" class="pin-wrapper">
                <span class="pin-label">Pin</span>
                <input type="number" min="0" max="39" class="field pin-field"
                  :value="selectedSensors.get(s.sensorId)"
                  @input="e => setSensorPin(s.sensorId, Number((e.target as HTMLInputElement).value))" />
              </div>
            </div>
          </div>
        </div>

        <p v-if="createError" class="field-error">{{ createError }}</p>
        <div class="modal-footer">
          <AppButton variant="ghost" @click="showCreate = false">Cancelar</AppButton>
          <AppButton variant="primary" :loading="creating" @click="doCreate">Criar</AppButton>
        </div>
      </div>
    </div>

  </AppLayout>
</template>

<style scoped>
.toolbar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-6); }
.toolbar-actions { display: flex; gap: var(--space-2); }

.block + .block { margin-top: var(--space-6); }
.block-title { display: flex; align-items: baseline; gap: var(--space-2); font-size: var(--text-sm); font-weight: 600; color: var(--text-secondary); margin: 0 0 var(--space-2); }
.count { font-weight: 400; color: var(--text-muted); font-family: var(--font-mono); font-size: var(--text-xs); }

.prov-line {
  display: flex; align-items: center; gap: var(--space-3); width: 100%;
  padding: var(--space-3) var(--space-4);
  background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg);
  color: var(--text); font-family: var(--font-sans); font-size: var(--text-sm); text-align: left;
  cursor: pointer; transition: border-color var(--transition);
}
.prov-line:hover { border-color: var(--border-strong); }
.prov-line .chevron { margin-left: auto; color: var(--text-muted); }
.prov-line:hover .chevron { color: var(--accent); }
.prov-empty { font-size: var(--text-sm); color: var(--text-muted); margin: 0; padding: var(--space-3) 0; }
.link-btn { background: none; border: none; padding: 0; margin-left: var(--space-1); color: var(--accent); font: inherit; cursor: pointer; }
.link-btn:hover { text-decoration: underline; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: var(--space-3) var(--space-4); text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) var(--space-4); border-top: 1px solid var(--border); font-size: var(--text-sm); }
.tbl-row { cursor: pointer; transition: background var(--transition); }
.tbl-row:hover td { background: var(--panel); }
.ver-status { margin-left: var(--space-2); font-size: var(--text-xs); }
.empty-row { color: var(--text-muted); }

.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; color: var(--text); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.text-danger { color: var(--danger); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }


.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 520px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow-y: auto; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.title-chip { margin-left: var(--space-2); font-size: var(--text-sm); font-weight: 500; color: var(--text-muted); }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }

.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; resize: vertical; width: 100%; box-sizing: border-box; }
.field:focus { border-color: var(--primary); }
.checkbox-row { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); cursor: pointer; }
.field-error { color: var(--danger); font-size: var(--text-sm); margin: 0; }

.sensor-select-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 200px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2); }
.sensor-select-row { display: flex; align-items: center; gap: var(--space-2); }
.pin-wrapper { display: flex; align-items: center; gap: var(--space-1); flex-shrink: 0; }
.pin-label { font-size: var(--text-sm); color: var(--text-muted); white-space: nowrap; }
.pin-field { width: 60px; padding: 4px 8px; resize: none; }

</style>
