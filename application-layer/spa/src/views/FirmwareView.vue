<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { firmwareApi } from '@/services/firmware'
import { devicesApi } from '@/services/devices'
import { sensorsApi } from '@/services/sensors'
import { groupsApi } from '@/services/groups'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const firmwares = ref<any[]>([])
const loading = ref(true)

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'
const statusVariant = (s: string): BadgeVariant =>
  ({ STAGED: 'muted', DEPLOYED: 'success', DEPRECATED: 'danger' }[s] as BadgeVariant) ?? 'muted'
const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'
const bytes = (n: number) => n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${(n / 1024).toFixed(0)} KB`

// ── Firmware list ─────────────────────────────────────────────────────────────
const load = async () => {
  const r = await firmwareApi.list()
  firmwares.value = Array.isArray(r.data) ? r.data : []
}

const provisioningFirmware = computed(() =>
  firmwares.value.find(fw => fw.provisioningFirmware) ?? null
)

// ── Detail modal ──────────────────────────────────────────────────────────────
const showDetail = ref(false)
const detailFw = ref<any>(null)
const detailDevices = ref<any[]>([])
const loadingDetailDevices = ref(false)

const openDetail = async (fw: any) => {
  detailFw.value = fw
  showDetail.value = true
  loadingDetailDevices.value = true
  try {
    const r = await devicesApi.list()
    const all = Array.isArray(r.data) ? r.data : (r.data?.content ?? [])
    detailDevices.value = all.filter((d: any) => d.firmwareVersion === fw.version)
  } finally {
    loadingDetailDevices.value = false }
}

// ── Upload modal ──────────────────────────────────────────────────────────────
const showUpload = ref(false)
const uploadFile = ref<File | null>(null)
const uploadMeta = ref({ version: '', releaseNotes: '', isProvisioning: false })
const uploading = ref(false)
const uploadError = ref('')

const availableSensors = ref<any[]>([])
const selectedSensors = ref<Map<string, number>>(new Map()) // sensorId → pin

const openUpload = async (asProvisioning = false) => {
  uploadFile.value = null
  uploadMeta.value = { version: '', releaseNotes: '', isProvisioning: asProvisioning }
  uploadError.value = ''
  selectedSensors.value = new Map()
  showUpload.value = true
  if (!availableSensors.value.length) {
    const r = await sensorsApi.list()
    availableSensors.value = r.data?.data ?? r.data ?? []
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
    await firmwareApi.upload(uploadFile.value, { ...uploadMeta.value, sensors })
    showUpload.value = false
    await load()
  } catch (e: any) {
    uploadError.value = e.response?.data?.message ?? 'Erro ao fazer upload.'
  } finally { uploading.value = false }
}

// ── Generate package modal ────────────────────────────────────────────────────
const showPackage = ref(false)
const packageForm = ref({ deviceName: '', wifiSsid: '', wifiPass: '', groupId: '' })
const showWifiPass = ref(false)
const generating = ref(false)
const generateError = ref('')
const groups = ref<any[]>([])

const openPackage = async () => {
  packageForm.value = { deviceName: '', wifiSsid: '', wifiPass: '', groupId: '' }
  showWifiPass.value = false
  generateError.value = ''
  showPackage.value = true
  if (!groups.value.length) {
    try { groups.value = (await groupsApi.list()).data ?? [] } catch { /* grupos são opcionais */ }
  }
}

const doGenerate = async () => {
  const { deviceName, wifiSsid, wifiPass, groupId } = packageForm.value
  if (!deviceName.trim() || !wifiSsid.trim() || !wifiPass.trim()) {
    generateError.value = 'Todos os campos são obrigatórios.'
    return
  }
  generating.value = true; generateError.value = ''
  try {
    const payload: any = { deviceName: deviceName.trim(), wifiSsid: wifiSsid.trim(), wifiPass: wifiPass.trim() }
    if (groupId) payload.groupId = groupId
    const r = await firmwareApi.generatePackage(payload)
    const url = URL.createObjectURL(r.data)
    const a = document.createElement('a')
    a.href = url
    a.download = `flash_package_${deviceName.trim().replace(/[^a-zA-Z0-9_-]/g, '_')}.zip`
    a.click()
    URL.revokeObjectURL(url)
    showPackage.value = false
  } catch (e: any) {
    generateError.value = e.response?.data?.message ?? 'Erro ao gerar pacote.'
  } finally { generating.value = false }
}

const openUploadFromPackage = () => {
  showPackage.value = false
  openUpload(true)
}

// ── Deploy modal ──────────────────────────────────────────────────────────────
const showDeploy = ref(false)
const deployFirmwareId = ref('')
const deployFirmwareVersion = ref('')
const selectedDeployIds = ref<Set<string>>(new Set())
const allDevices = ref<any[]>([])
const loadingDevices = ref(false)
const deploying = ref(false)
const deployError = ref('')

const openDeploy = async (firmwareId: string, version: string) => {
  deployFirmwareId.value = firmwareId
  deployFirmwareVersion.value = version
  selectedDeployIds.value = new Set()
  deployError.value = ''
  showDeploy.value = true
  if (!allDevices.value.length) {
    loadingDevices.value = true
    try {
      const r = await devicesApi.list()
      allDevices.value = Array.isArray(r.data) ? r.data : (r.data?.content ?? [])
    } finally { loadingDevices.value = false }
  }
}

const toggleDeployDevice = (id: string) => {
  const next = new Set(selectedDeployIds.value)
  next.has(id) ? next.delete(id) : next.add(id)
  selectedDeployIds.value = next
}

const deployableDevices = computed(() =>
  allDevices.value.filter(d => d.status !== 'REVOKED')
)

const doDeploy = async () => {
  if (!selectedDeployIds.value.size) { deployError.value = 'Selecione ao menos um device.'; return }
  deploying.value = true; deployError.value = ''
  try {
    await firmwareApi.deploy(deployFirmwareId.value, [...selectedDeployIds.value])
    showDeploy.value = false
  } catch (e: any) {
    deployError.value = e.response?.data?.message ?? 'Erro ao enviar deploy.'
  } finally { deploying.value = false }
}

// ── Actions ───────────────────────────────────────────────────────────────────
const doDeprecate = async (id: string) => {
  if (!confirm('Marcar firmware como DEPRECATED?')) return
  await firmwareApi.deprecate(id); await load()
}

const doSetProvisioning = async (id: string) => {
  if (!confirm('Definir como firmware de provisionamento? O firmware atual de provisioning perderá esse status.')) return
  try {
    await firmwareApi.setProvisioning(id); await load()
  } catch (e: any) {
    alert(e.response?.data?.message ?? 'Erro ao definir firmware de provisioning.')
  }
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Firmware">
      <template #actions>
        <div class="actions-row" v-if="authStore.isAdmin">
          <AppButton size="lg" variant="secondary" @click="openPackage">Gerar Pacote Flash</AppButton>
          <AppButton size="lg" variant="primary" @click="openUpload(false)">Upload Firmware</AppButton>
        </div>
      </template>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Nome</th><th>Versão</th><th>Status</th><th>Provisioning</th>
            <th>Enviado por</th><th>Data upload</th><th v-if="authStore.isAdmin">Ações</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="fw in firmwares" :key="fw.firmwareId" class="tbl-row" @click="openDetail(fw)">
            <td class="text-sm fw-name">{{ fw.originalFilename ?? fw.filename }}</td>
            <td class="mono font-medium">v{{ fw.version }}</td>
            <td><AppBadge :variant="statusVariant(fw.status)">{{ fw.status }}</AppBadge></td>
            <td>
              <AppBadge v-if="fw.provisioningFirmware" variant="primary" dot>Sim</AppBadge>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="text-sm">{{ fw.createdByUsername ?? '—' }}</td>
            <td class="text-muted text-sm">{{ fmt(fw.uploadedAt) }}</td>
            <td v-if="authStore.isAdmin" @click.stop>
              <div class="row-actions">
                <AppButton size="sm" variant="secondary" :disabled="fw.status === 'DEPRECATED'"
                  @click="openDeploy(fw.firmwareId, fw.version)">Deploy</AppButton>
                <AppButton size="sm" variant="ghost"
                  :disabled="fw.provisioningFirmware || fw.status === 'DEPRECATED'"
                  @click="doSetProvisioning(fw.firmwareId)">Provisioning</AppButton>
                <AppButton size="sm" variant="danger" :disabled="fw.status === 'DEPRECATED'"
                  @click="doDeprecate(fw.firmwareId)">Deprecar</AppButton>
              </div>
            </td>
          </tr>
          <tr v-if="!firmwares.length">
            <td colspan="7" class="empty">Nenhum firmware cadastrado</td>
          </tr>
        </tbody>
      </table>
    </AppCard>

    <!-- ── Detail modal ──────────────────────────────────────────────────────── -->
    <div v-if="showDetail && detailFw" class="modal-overlay" @click.self="showDetail = false">
      <div class="modal modal-wide">
        <div class="detail-header">
          <div>
            <h3 class="modal-title">{{ detailFw.originalFilename ?? detailFw.filename }}</h3>
            <div class="detail-sub">
              <span class="mono">v{{ detailFw.version }}</span>
              <AppBadge :variant="statusVariant(detailFw.status)">{{ detailFw.status }}</AppBadge>
              <AppBadge v-if="detailFw.provisioningFirmware" variant="primary" dot>Provisioning</AppBadge>
            </div>
          </div>
        </div>

        <div class="detail-grid">
          <div class="detail-item">
            <span class="detail-label">Tamanho</span>
            <span class="detail-value">{{ bytes(detailFw.sizeBytes) }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Deploys confirmados</span>
            <span class="detail-value">{{ detailFw.deployCount }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Enviado por</span>
            <span class="detail-value">{{ detailFw.createdByUsername ?? '—' }}</span>
          </div>
          <div class="detail-item">
            <span class="detail-label">Data de upload</span>
            <span class="detail-value">{{ fmt(detailFw.uploadedAt) }}</span>
          </div>
          <div class="detail-item detail-full">
            <span class="detail-label">SHA-256</span>
            <span class="detail-value mono text-xs">{{ detailFw.sha256 }}</span>
          </div>
          <div v-if="detailFw.releaseNotes" class="detail-item detail-full">
            <span class="detail-label">Release Notes</span>
            <span class="detail-value text-sm pre">{{ detailFw.releaseNotes }}</span>
          </div>
        </div>

        <div v-if="detailFw.sensorConfigs?.length" class="detail-section">
          <p class="section-title">Sensores configurados</p>
          <div class="sensor-chips">
            <div v-for="s in detailFw.sensorConfigs" :key="s.sensorName" class="sensor-chip">
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

        <div class="modal-footer">
          <AppButton variant="ghost" @click="showDetail = false">Fechar</AppButton>
        </div>
      </div>
    </div>

    <!-- ── Upload modal ──────────────────────────────────────────────────────── -->
    <div v-if="showUpload" class="modal-overlay" @click.self="showUpload = false">
      <div class="modal">
        <h3 class="modal-title">Upload de Firmware</h3>
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
        <label class="checkbox-row">
          <input type="checkbox" v-model="uploadMeta.isProvisioning" />
          Firmware de provisionamento
        </label>

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
                <input
                  type="number" min="0" max="39"
                  class="field pin-field"
                  :value="selectedSensors.get(s.sensorId)"
                  @input="e => setSensorPin(s.sensorId, Number((e.target as HTMLInputElement).value))"
                />
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

    <!-- ── Gerar Pacote Flash modal ───────────────────────────────────────────── -->
    <div v-if="showPackage" class="modal-overlay" @click.self="showPackage = false">
      <div class="modal">
        <h3 class="modal-title">Gerar Pacote Flash</h3>

        <div v-if="!provisioningFirmware" class="prov-warning">
          <svg class="warn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
          <div>
            <p class="warn-title">Sem firmware de provisionamento registrado</p>
            <p class="warn-desc">É necessário ter um firmware de provisioning registrado no sistema para gerar o pacote flash.</p>
          </div>
        </div>

        <div v-else class="prov-ok">
          <svg class="ok-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
          <div>
            <p class="ok-title">Firmware de provisionamento</p>
            <p class="ok-desc">
              <span class="mono">v{{ provisioningFirmware.version }}</span>
              <AppBadge :variant="statusVariant(provisioningFirmware.status)" class="ok-badge">{{ provisioningFirmware.status }}</AppBadge>
            </p>
          </div>
        </div>

        <template v-if="provisioningFirmware">
          <div class="form-group">
            <label>Nome do dispositivo</label>
            <input v-model="packageForm.deviceName" class="field" placeholder="ex: sensor-armazem-01" />
          </div>
          <div class="form-group">
            <label>SSID da rede WiFi</label>
            <input v-model="packageForm.wifiSsid" class="field" placeholder="MinhaRede" autocomplete="off" />
          </div>
          <div class="form-group">
            <label>Senha WiFi</label>
            <div class="pass-row">
              <input v-model="packageForm.wifiPass"
                :type="showWifiPass ? 'text' : 'password'"
                class="field pass-field" placeholder="••••••••" autocomplete="new-password" />
              <button class="pass-toggle" @click="showWifiPass = !showWifiPass" type="button">
                {{ showWifiPass ? 'Ocultar' : 'Mostrar' }}
              </button>
            </div>
          </div>
          <div class="form-group">
            <label>Grupo <span class="text-muted">(opcional)</span></label>
            <select v-model="packageForm.groupId" class="field">
              <option value="">— Nenhum —</option>
              <option v-for="g in groups" :key="g.groupId" :value="g.groupId">{{ g.name }}</option>
            </select>
          </div>
        </template>

        <p v-if="generateError" class="field-error">{{ generateError }}</p>
        <div class="modal-footer" :style="!provisioningFirmware ? 'flex-direction: row-reverse; justify-content: flex-start; gap: 100px' : ''">
          <AppButton v-if="!provisioningFirmware" variant="primary" @click="openUploadFromPackage">
            Registrar Firmware de Provisionamento
          </AppButton>
          <div style="display:flex;gap:8px">
            <AppButton variant="ghost" @click="showPackage = false">Cancelar</AppButton>
            <AppButton v-if="provisioningFirmware" variant="primary" :loading="generating" @click="doGenerate">
              Gerar e Baixar
            </AppButton>
          </div>
        </div>
      </div>
    </div>

    <!-- ── Deploy modal ──────────────────────────────────────────────────────── -->
    <div v-if="showDeploy" class="modal-overlay" @click.self="showDeploy = false">
      <div class="modal">
        <h3 class="modal-title">Deploy — <span class="mono">v{{ deployFirmwareVersion }}</span></h3>
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
.actions-row { display: flex; gap: var(--space-2); }

/* ── Table ─────────────────────────────────────────────────────────────────── */
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); }
.tbl-row { cursor: pointer; transition: background var(--transition); }
.tbl-row:hover td { background: var(--panel); }
.fw-name { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.empty-sm { color: var(--text-muted); font-size: var(--text-sm); padding: var(--space-2) 0; }
.row-actions { display: flex; gap: var(--space-2); }

/* ── Modal shell ────────────────────────────────────────────────────────────── */
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 520px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow-y: auto; }
.modal-wide { width: 640px; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-desc { font-size: var(--text-sm); color: var(--text-muted); margin: 0; }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }

/* ── Detail modal ───────────────────────────────────────────────────────────── */
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-sub { display: flex; align-items: center; gap: var(--space-2); margin-top: var(--space-1); }
.detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
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

/* ── Form fields ────────────────────────────────────────────────────────────── */
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; resize: vertical; width: 100%; box-sizing: border-box; }
.field:focus { border-color: var(--primary); }
.pass-row { display: flex; gap: var(--space-2); }
.pass-field { flex: 1; }
.pass-toggle { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-xs); color: var(--text-muted); cursor: pointer; white-space: nowrap; }
.pass-toggle:hover { border-color: var(--primary); color: var(--text); }
.checkbox-row { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); cursor: pointer; }
.field-error { color: var(--danger); font-size: var(--text-sm); margin: 0; }

/* ── Sensor selection in upload ─────────────────────────────────────────────── */
.sensor-select-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 200px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2); }
.sensor-select-row { display: flex; align-items: center; gap: var(--space-2); }
.pin-wrapper { display: flex; align-items: center; gap: var(--space-1); flex-shrink: 0; }
.pin-label { font-size: var(--text-sm); color: var(--text-muted); white-space: nowrap; }
.pin-field { width: 60px; padding: 4px 8px; resize: none; }

/* ── Provisioning banners ────────────────────────────────────────────────────── */
.prov-warning { display: flex; gap: var(--space-3); padding: var(--space-4); background: rgba(239,68,68,.08); border: 1px solid rgba(239,68,68,.25); border-radius: var(--radius-md); }
.warn-icon { width: 20px; height: 20px; color: var(--danger); flex-shrink: 0; margin-top: 2px; }
.warn-title { font-size: var(--text-sm); font-weight: 600; color: var(--danger); margin: 0 0 4px; }
.warn-desc { font-size: var(--text-xs); color: var(--text-muted); margin: 0; line-height: 1.5; }
.prov-ok { display: flex; gap: var(--space-3); padding: var(--space-3) var(--space-4); background: rgba(16,185,129,.08); border: 1px solid rgba(16,185,129,.25); border-radius: var(--radius-md); align-items: center; }
.ok-icon { width: 18px; height: 18px; color: var(--success); flex-shrink: 0; }
.ok-title { font-size: var(--text-xs); color: var(--text-muted); margin: 0; }
.ok-desc { display: flex; align-items: center; gap: var(--space-2); margin: 4px 0 0; font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.ok-badge { flex-shrink: 0; }

/* ── Deploy device chips ────────────────────────────────────────────────────── */
.device-grid { display: flex; flex-wrap: wrap; gap: var(--space-2); overflow-y: auto; max-height: 280px; align-content: flex-start; }
.device-chip { display: flex; align-items: center; gap: var(--space-2); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2) var(--space-3); cursor: pointer; transition: border-color var(--transition), background var(--transition); }
.device-chip:hover { border-color: var(--primary); }
.device-chip.selected { border-color: var(--primary); background: var(--primary-dim); }
.chip-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.chip-ver { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); }
</style>
