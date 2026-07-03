<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { firmwareApi } from '@/services/firmware'
import { devicesApi } from '@/services/devices'
import { sensorsApi } from '@/services/sensors'
import { groupsApi } from '@/services/groups'
import { useAuthStore } from '@/stores/auth'
import type {
  FirmwareResponseDTO,
  FirmwareVersionResponseDTO,
  FirmwareVersionSummaryDTO,
  DeviceGroupResponseDTO,
  DeviceSummaryDTO,
  SensorResponseDTO,
  UploadVersionRequest
} from '@/types/models'
import { errorMessage } from '@/utils/errors'

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

const firmwareId = route.params.firmwareId as string

const firmware = ref<FirmwareResponseDTO | null>(null)
const versions = ref<FirmwareVersionSummaryDTO[]>([])
const groups = ref<DeviceGroupResponseDTO[]>([])
const loading = ref(true)

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'
const statusVariant = (s: string): BadgeVariant =>
  ({ STAGED: 'muted', DEPLOYED: 'success', DEPRECATED: 'danger' }[s] as BadgeVariant) ?? 'muted'
const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'
const bytes = (n: number) => n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${(n / 1024).toFixed(0)} KB`

const managedGroupIds = computed(() =>
  new Set(groups.value.filter(g => g.myRole === 'MEMBER' || g.myRole === 'OWNER').map(g => g.groupId))
)

const groupName = (ownerGroupId: string | null) => {
  if (!ownerGroupId) return 'Plataforma'
  return groups.value.find(g => g.groupId === ownerGroupId)?.name ?? ownerGroupId
}

const canManage = computed(() => {
  if (!firmware.value) return false
  if (authStore.isAdmin) return true
  return firmware.value.ownerGroupId && managedGroupIds.value.has(firmware.value.ownerGroupId)
})

const load = async () => {
  const [fwRes, vRes] = await Promise.all([
    firmwareApi.get(firmwareId),
    firmwareApi.listVersions(firmwareId),
  ])
  firmware.value = fwRes.data
  versions.value = Array.isArray(vRes.data) ? vRes.data :  []
}

// ── Version detail modal  ────────────────────────────────────────────
const showDetail = ref(false)
const detailVersion = ref<FirmwareVersionResponseDTO | null>(null)
const detailDevices = ref<DeviceSummaryDTO[]>([])
const loadingDetailDevices = ref(false)

const openVersionDetail = async (versionSummary: FirmwareVersionSummaryDTO) => {
  detailVersion.value = null
  showDetail.value = true
  loadingDetailDevices.value = true
  try {
    const [vRes, dRes] = await Promise.all([
      firmwareApi.getVersion(versionSummary.versionId),
      devicesApi.list(),
    ])
    detailVersion.value = vRes.data
    const all = Array.isArray(dRes.data) ? dRes.data : []
    detailDevices.value = all.filter(d => d.firmwareVersionId === versionSummary.versionId)
  } finally { loadingDetailDevices.value = false }
}

// ── Upload New Version modal ──────────────────────────────────────────────────
const showUpload = ref(false)
const uploadFile = ref<File | null>(null)
const uploadMeta = ref({ version: '', releaseNotes: '' })
const uploading = ref(false)
const uploadError = ref('')
const availableSensors = ref<SensorResponseDTO[]>([])
const selectedSensors = ref<Map<string, number>>(new Map())

const openUpload = async () => {
  uploadFile.value = null
  uploadMeta.value = { version: '', releaseNotes: '' }
  uploadError.value = ''
  selectedSensors.value = new Map()
  showUpload.value = true
  if (!availableSensors.value.length) {
    const r = await sensorsApi.list()
    availableSensors.value = Array.isArray(r.data) ? r.data : []
  }
}

const toggleSensor = (sensorId: string) => {
  const next = new Map(selectedSensors.value)
  next.has(sensorId) ? next.delete(sensorId) : next.set(sensorId, 0)
  selectedSensors.value = next
}

const setSensorPin = (sensorId: string, pin: number) => {
  const next = new Map(selectedSensors.value)
  next.set(sensorId, pin)
  selectedSensors.value = next
}

const doUpload = async () => {
  if (!uploadFile.value || !uploadMeta.value.version.trim()) {
    uploadError.value = 'Arquivo e versão são obrigatórios.'
    return
  }
  uploading.value = true; uploadError.value = ''
  try {
    const sensors = [...selectedSensors.value.entries()].map(([sensorId, pin]) => ({ sensorId, pin }))
    
    const payload: UploadVersionRequest = {
      version: uploadMeta.value.version.trim(),
      releaseNotes: uploadMeta.value.releaseNotes || null,
      sensors,
    }

    await firmwareApi.uploadVersion(firmwareId, uploadFile.value, payload as unknown as Record<string, unknown>)
    showUpload.value = false
    await load()
  } catch (e: unknown) {
    uploadError.value = errorMessage(e, 'Erro ao subir nova versão.')
  } finally { uploading.value = false }
}

// ── Deploy modal ──────────────────────────────────────────────────────────────
const showDeploy = ref(false)
const deployVersionId = ref('')
const deployVersionString = ref('')
const selectedDeployIds = ref<Set<string>>(new Set())
const allDevices = ref<DeviceSummaryDTO[]>([])
const loadingDevices = ref(false)
const deploying = ref(false)
const deployError = ref('')

const openDeploy = async (versionSummary: FirmwareVersionSummaryDTO) => {
  deployVersionId.value = versionSummary.versionId
  deployVersionString.value = versionSummary.version
  selectedDeployIds.value = new Set()
  deployError.value = ''
  showDeploy.value = true
  if (!allDevices.value.length) {
    loadingDevices.value = true
    try {
      const r = await devicesApi.list()
      allDevices.value = Array.isArray(r.data) ? r.data :  []
    } finally { loadingDevices.value = false }
  }
}

const toggleDeployDevice = (id: string) => {
  const next = new Set(selectedDeployIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selectedDeployIds.value = next
}

const deployableDevices = computed(() => allDevices.value.filter(d => d.status !== 'REVOKED'))

const doDeploy = async () => {
  if (!selectedDeployIds.value.size) { deployError.value = 'Selecione ao menos um device.'; return }
  deploying.value = true; deployError.value = ''
  try {
    await firmwareApi.deploy(deployVersionId.value, [...selectedDeployIds.value])
    showDeploy.value = false
  } catch (e: unknown) {
    deployError.value = errorMessage(e, 'Erro ao enviar deploy.')
  } finally { deploying.value = false }
}

// ── Deprecate version ────────────────────────────────────────────────────────
const doDeprecate = async (v: FirmwareVersionSummaryDTO) => {
  const msg = `Você tem certeza que quer marcar a versão v${v.version} como obsoleta?\n\n` +
              `Esta ação é IRREVERSÍVEL. Para desfazer, será necessário subir uma nova versão com o fix.`
  if (!confirm(msg)) return
  try {
    await firmwareApi.deprecate(v.versionId); await load()
  } catch (e: unknown) {
    alert(errorMessage(e, 'Erro ao deprecar versão.'))
  }
}

onMounted(async () => {
  try {
    const [_, grpRes] = await Promise.all([load(), groupsApi.list()])
    groups.value = grpRes.data ?? []
  } catch {
    router.push('/firmware')
  } finally { loading.value = false }
})
</script>

<template>
  <AppLayout>
    <div v-if="loading" class="loading">Carregando...</div>

    <div v-else-if="firmware" class="detail">
      <!-- Header -->
      <div class="detail-header">
        <div>
          <button class="back-btn" @click="router.push('/firmware')">← Firmware</button>
          <h1 class="firmware-name">{{ firmware.firmwareName }}</h1>
          <div class="firmware-meta">
            <span v-if="!firmware.ownerGroupId" class="owner-platform">Plataforma</span>
            <span v-else class="owner-group">{{ groupName(firmware.ownerGroupId) }}</span>
            <AppBadge v-if="firmware.provisioningFirmware" variant="primary" dot>Provisioning</AppBadge>
            <span class="text-muted text-sm">criado por {{ firmware.createdByUsername ?? '—' }} em {{ fmt(firmware.createdAt) }}</span>
          </div>
          <p v-if="firmware.description" class="firmware-desc">{{ firmware.description }}</p>
        </div>
        <AppButton v-if="canManage" variant="primary" size="lg" @click="openUpload">+ Nova Versão</AppButton>
      </div>

      <!-- Versions table -->
      <AppCard title="Versões">
        <table class="tbl">
          <thead>
            <tr>
              <th>Versão</th>
              <th>Status</th>
              <th>Enviado por</th>
              <th>Data upload</th>
              <th>Deploys</th>
              <th>Tamanho</th>
              <th v-if="canManage">Ações</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="v in versions" :key="v.versionId" class="tbl-row" @click="openVersionDetail(v)">
              <td class="mono font-medium">v{{ v.version }}</td>
              <td><AppBadge :variant="statusVariant(v.status)">{{ v.status }}</AppBadge></td>
              <td class="text-sm">{{ v.createdByUsername ?? '—' }}</td>
              <td class="text-muted text-sm">{{ fmt(v.uploadedAt) }}</td>
              <td class="text-sm">{{ v.deployCount }}</td>
              <td class="text-muted text-sm">{{ bytes(v.sizeBytes) }}</td>
              <td v-if="canManage" @click.stop>
                <div class="row-actions">
                  <AppButton size="sm" variant="secondary"
                    :disabled="v.status === 'DEPRECATED'"
                    @click="openDeploy(v)">Deploy</AppButton>
                  <AppButton size="sm" variant="danger"
                    :disabled="v.status === 'DEPRECATED'"
                    @click="doDeprecate(v)">Deprecar</AppButton>
                </div>
              </td>
            </tr>
            <tr v-if="!versions.length">
              <td :colspan="canManage ? 7 : 6" class="empty">Nenhuma versão registrada</td>
            </tr>
          </tbody>
        </table>
      </AppCard>
    </div>

    <!-- ── Version detail modal (nível 3) ──────────────────────────────────── -->
    <div v-if="showDetail" class="modal-overlay" @click.self="showDetail = false">
      <div class="modal modal-wide">
        <div v-if="!detailVersion" class="empty">Carregando...</div>
        <template v-else>
          <div class="detail-modal-header">
            <div>
              <h3 class="modal-title">{{ detailVersion.originalFilename ?? detailVersion.filename }}</h3>
              <div class="detail-sub">
                <span class="mono">v{{ detailVersion.version }}</span>
                <AppBadge :variant="statusVariant(detailVersion.status)">{{ detailVersion.status }}</AppBadge>
                <AppBadge v-if="detailVersion.provisioningFirmware" variant="primary" dot>Provisioning</AppBadge>
                <span class="text-muted text-xs">{{ groupName(detailVersion.ownerGroupId) }}</span>
              </div>
            </div>
          </div>

          <div class="detail-grid">
            <div class="detail-item">
              <span class="detail-label">Tamanho</span>
              <span class="detail-value">{{ bytes(detailVersion.sizeBytes) }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">Deploys confirmados</span>
              <span class="detail-value">{{ detailVersion.deployCount }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">Enviado por</span>
              <span class="detail-value">{{ detailVersion.createdByUsername ?? '—' }}</span>
            </div>
            <div class="detail-item">
              <span class="detail-label">Data de upload</span>
              <span class="detail-value">{{ fmt(detailVersion.uploadedAt) }}</span>
            </div>
            <div class="detail-item detail-full">
              <span class="detail-label">SHA-256</span>
              <span class="detail-value mono text-xs">{{ detailVersion.sha256 }}</span>
            </div>
            <div v-if="detailVersion.releaseNotes" class="detail-item detail-full">
              <span class="detail-label">Release Notes</span>
              <span class="detail-value text-sm pre">{{ detailVersion.releaseNotes }}</span>
            </div>
          </div>

          <div v-if="detailVersion.sensorConfigs?.length" class="detail-section">
            <p class="section-title">Sensores configurados</p>
            <div class="sensor-chips">
              <div v-for="s in detailVersion.sensorConfigs" :key="s.sensorName" class="sensor-chip">
                <span class="chip-name">{{ s.sensorName }}</span>
                <span class="chip-pin">pino {{ s.pin }}</span>
              </div>
            </div>
          </div>

          <div class="detail-section">
            <p class="section-title">Dispositivos nesta versão</p>
            <div v-if="loadingDetailDevices" class="empty-sm">Carregando...</div>
            <div v-else-if="!detailDevices.length" class="empty-sm text-muted">Nenhum dispositivo nesta versão.</div>
            <div v-else class="device-pills">
              <span v-for="d in detailDevices" :key="d.deviceId" class="device-pill">{{ d.name }}</span>
            </div>
          </div>
        </template>

        <div class="modal-footer">
          <AppButton variant="ghost" @click="showDetail = false">Fechar</AppButton>
        </div>
      </div>
    </div>

    <!-- ── Upload New Version modal ─────────────────────────────────────────── -->
    <div v-if="showUpload" class="modal-overlay" @click.self="showUpload = false">
      <div class="modal">
        <h3 class="modal-title">Nova Versão de {{ firmware?.firmwareName }}</h3>

        <div class="form-group">
          <label>Arquivo .bin</label>
          <input type="file" accept=".bin" @change="e => uploadFile = (e.target as HTMLInputElement).files?.[0] ?? null" />
        </div>

        <div class="form-group">
          <label>Versão</label>
          <input v-model="uploadMeta.version" class="field" placeholder="ex: 1.0.3" />
        </div>

        <div class="form-group">
          <label>Release Notes</label>
          <textarea v-model="uploadMeta.releaseNotes" class="field" rows="3" placeholder="Descreva as mudanças..." />
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

        <p v-if="uploadError" class="field-error">{{ uploadError }}</p>
        <div class="modal-footer">
          <AppButton variant="ghost" @click="showUpload = false">Cancelar</AppButton>
          <AppButton variant="primary" :loading="uploading" @click="doUpload">Enviar</AppButton>
        </div>
      </div>
    </div>

    <!-- ── Deploy modal ─────────────────────────────────────────────────────── -->
    <div v-if="showDeploy" class="modal-overlay" @click.self="showDeploy = false">
      <div class="modal">
        <h3 class="modal-title">Deploy — <span class="mono">v{{ deployVersionString }}</span></h3>
        <p class="modal-desc">Selecione os dispositivos que receberão o update via OTA.</p>

        <div v-if="loadingDevices" class="empty">Carregando dispositivos...</div>
        <div v-else-if="!deployableDevices.length" class="empty">Nenhum dispositivo disponível.</div>
        <div v-else class="device-grid">
          <button v-for="d in deployableDevices" :key="d.deviceId"
            class="device-chip" :class="{ selected: selectedDeployIds.has(d.deviceId) }"
            @click="toggleDeployDevice(d.deviceId)">
            <span class="chip-name">{{ d.name }}</span>
            <span class="chip-ver">{{ d.firmwareVersion ? `v${d.firmwareVersion}` : '—' }}</span>
            <AppBadge :variant="(({ ACTIVE: 'success', COMMAND_PENDING: 'warning', ERROR: 'danger' } as any)[d.status] ?? 'muted')" dot />
          </button>
        </div>

        <p v-if="deployError" class="field-error">{{ deployError }}</p>
        <div class="modal-footer">
          <span class="text-muted text-sm">{{ selectedDeployIds.size }} selecionado(s)</span>
          <div style="display:flex;gap:8px">
            <AppButton variant="ghost" @click="showDeploy = false">Cancelar</AppButton>
            <AppButton variant="primary" :loading="deploying" @click="doDeploy">Enviar OTA</AppButton>
          </div>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.loading { color: var(--text-muted); padding: var(--space-8); text-align: center; }
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-4); flex-wrap: wrap; }
.back-btn { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--space-2); padding: 0; }
.back-btn:hover { color: var(--text); }
.firmware-name { font-size: var(--text-2xl); font-weight: 700; color: var(--text); margin: 0 0 var(--space-2); }
.firmware-meta { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; }
.firmware-desc { color: var(--text-muted); font-size: var(--text-sm); margin: var(--space-2) 0 0; }

.owner-platform { font-size: var(--text-xs); background: var(--primary-dim, rgba(99,102,241,.1)); color: var(--primary); border-radius: var(--radius-sm); padding: 2px 8px; font-weight: 500; }
.owner-group { font-size: var(--text-xs); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 2px 8px; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); font-size: var(--text-sm); }
.tbl-row { cursor: pointer; transition: background var(--transition); }
.tbl-row:hover td { background: var(--panel); }
.row-actions { display: flex; gap: var(--space-2); flex-wrap: wrap; }

.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; color: var(--text); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.empty-sm { color: var(--text-muted); font-size: var(--text-sm); padding: var(--space-2) 0; }

/* Modal shell */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 520px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow-y: auto; }
.modal-wide { width: 640px; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-desc { font-size: var(--text-sm); color: var(--text-muted); margin: 0; }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }

/* Detail modal (nível 3) */
.detail-modal-header { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-sub { display: flex; align-items: center; gap: var(--space-2); margin-top: var(--space-1); flex-wrap: wrap; }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: var(--space-3); }
.detail-full { grid-column: 1 / -1; }
.detail-item { display: flex; flex-direction: column; gap: 2px; }
.detail-label { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); }
.detail-value { font-size: var(--text-sm); color: var(--text); }
.pre { white-space: pre-wrap; line-height: 1.5; }
.detail-section { display: flex; flex-direction: column; gap: var(--space-2); }
.section-title { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); margin: 0; }
.sensor-chips { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.sensor-chip { display: flex; align-items: center; gap: var(--space-1); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-1) var(--space-2); font-size: var(--text-xs); }
.chip-pin { color: var(--text-muted); }
.device-pills { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.device-pill { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 2px var(--space-2); font-size: var(--text-xs); font-family: var(--font-mono); color: var(--text); }

/* Form fields */
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

/* Deploy device chips */
.device-grid { display: flex; flex-wrap: wrap; gap: var(--space-2); overflow-y: auto; max-height: 280px; align-content: flex-start; }
.device-chip { display: flex; align-items: center; gap: var(--space-2); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2) var(--space-3); cursor: pointer; transition: border-color var(--transition), background var(--transition); }
.device-chip:hover { border-color: var(--primary); }
.device-chip.selected { border-color: var(--primary); background: var(--primary-dim); }
.chip-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.chip-ver { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); }
</style>
