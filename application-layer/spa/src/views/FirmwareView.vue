<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { firmwareApi } from '@/services/firmware'
import { sensorsApi } from '@/services/sensors'
import { groupsApi } from '@/services/groups'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const router = useRouter()

const firmwares = ref<any[]>([])
const groups = ref<any[]>([])
const loading = ref(true)

type BadgeVariant = 'success' | 'warning' | 'danger' | 'info' | 'muted' | 'primary'
const statusVariant = (s: string): BadgeVariant =>
  ({ STAGED: 'muted', DEPLOYED: 'success', DEPRECATED: 'danger' }[s] as BadgeVariant) ?? 'muted'
const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

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

const provisioningFirmware = computed(() =>
  firmwares.value.find(fw => fw.provisioningFirmware) ?? null
)

const provisioningLatestDeprecated = computed(() =>
  provisioningFirmware.value?.latestVersion?.status === 'DEPRECATED'
)

const provisioningReady = computed(() =>
  !!provisioningFirmware.value && !provisioningLatestDeprecated.value
)

const load = async () => {
  const r = await firmwareApi.list()
  firmwares.value = Array.isArray(r.data) ? r.data : (r.data?.data ?? [])
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
  releaseNotes: '',
})
const creating = ref(false)
const createError = ref('')
const availableSensors = ref<any[]>([])
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
    releaseNotes: '',
  }
  createError.value = ''
  selectedSensors.value = new Map()
  showCreate.value = true
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
  creating.value = true; createError.value = ''
  try {
    const sensors = [...selectedSensors.value.entries()].map(([sensorId, pin]) => ({ sensorId, pin }))
    const payload: any = {
      firmwareName: meta.firmwareName.trim(),
      description: meta.description.trim() || null,
      initialVersion: meta.initialVersion.trim(),
      isProvisioning: meta.isProvisioning,
      releaseNotes: meta.releaseNotes,
      sensors,
    }
    if (!meta.isProvisioning && meta.ownerGroupId) {
      payload.ownerGroupId = meta.ownerGroupId
    }
    await firmwareApi.create(createFile.value, payload)
    showCreate.value = false
    await load()
  } catch (e: any) {
    createError.value = e.response?.data?.message ?? 'Erro ao criar firmware.'
  } finally { creating.value = false }
}

// ── Set Provisioning ──────────────────────────────────────────────────────────
const doSetProvisioning = async (id: string) => {
  if (!confirm('Definir como firmware de provisionamento? O provisioning atual perderá esse status.')) return
  try {
    await firmwareApi.setProvisioning(id); await load()
  } catch (e: any) {
    alert(e.response?.data?.message ?? 'Erro ao definir firmware de provisioning.')
  }
}

// ── Generate Flash Package ────────────────────────────────────────────────────
const showPackage = ref(false)
const packageForm = ref({ deviceName: '', wifiSsid: '', wifiPass: '', groupId: '' })
const showWifiPass = ref(false)
const generating = ref(false)
const generateError = ref('')

const openPackage = () => {
  packageForm.value = { deviceName: '', wifiSsid: '', wifiPass: '', groupId: '' }
  showWifiPass.value = false
  generateError.value = ''
  showPackage.value = true
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

const openCreateFromPackage = () => {
  showPackage.value = false
  openCreate(true)
}

// ── Navigation ────────────────────────────────────────────────────────────────
const goToDetail = (firmwareId: string) => router.push(`/firmware/${firmwareId}`)

onMounted(async () => {
  try {
    const [fwRes, grpRes] = await Promise.all([firmwareApi.list(), groupsApi.list()])
    firmwares.value = Array.isArray(fwRes.data) ? fwRes.data : (fwRes.data?.data ?? [])
    groups.value = grpRes.data ?? []
  } finally { loading.value = false }
})
</script>

<template>
  <AppLayout>
    <AppCard title="Firmware">
      <template #actions>
        <div class="actions-row" v-if="canCreate">
          <AppButton size="lg" variant="secondary" @click="openPackage">Gerar Pacote Flash</AppButton>
          <AppButton size="lg" variant="primary" @click="openCreate(false)">Novo Firmware</AppButton>
        </div>
      </template>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Nome</th>
            <th>Descrição</th>
            <th>Última versão</th>
            <th>Grupo</th>
            <th>Provisioning</th>
            <th>Enviado por</th>
            <th>Criado em</th>
            <th v-if="authStore.isAdmin">Ações</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="fw in firmwares" :key="fw.firmwareId" class="tbl-row" @click="goToDetail(fw.firmwareId)">
            <td class="font-medium">{{ fw.firmwareName }}</td>
            <td class="text-sm text-muted desc-cell">{{ fw.description ?? '—' }}</td>
            <td>
              <template v-if="fw.latestVersion">
                <span class="mono">v{{ fw.latestVersion.version }}</span>
                <AppBadge :variant="statusVariant(fw.latestVersion.status)" class="ml-1">{{ fw.latestVersion.status }}</AppBadge>
              </template>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="text-sm">
              <span v-if="!fw.ownerGroupId" class="owner-platform">Plataforma</span>
              <span v-else class="owner-group">{{ groupName(fw.ownerGroupId) }}</span>
            </td>
            <td>
              <AppBadge v-if="fw.provisioningFirmware" variant="primary" dot>Sim</AppBadge>
              <span v-else class="text-muted">—</span>
            </td>
            <td class="text-sm">{{ fw.createdByUsername ?? '—' }}</td>
            <td class="text-muted text-sm">{{ fmt(fw.createdAt) }}</td>
            <td v-if="authStore.isAdmin" @click.stop>
              <AppButton
                v-if="!fw.ownerGroupId && !fw.provisioningFirmware"
                size="sm" variant="ghost"
                @click="doSetProvisioning(fw.firmwareId)">Provisioning</AppButton>
            </td>
          </tr>
          <tr v-if="!firmwares.length">
            <td :colspan="authStore.isAdmin ? 8 : 7" class="empty">Nenhum firmware cadastrado</td>
          </tr>
        </tbody>
      </table>
    </AppCard>

    <!-- ── Create Firmware modal ────────────────────────────────────────────── -->
    <div v-if="showCreate" class="modal-overlay" @click.self="showCreate = false">
      <div class="modal">
        <h3 class="modal-title">Novo Firmware</h3>

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

        <div class="form-group">
          <label>Release Notes</label>
          <textarea v-model="createMeta.releaseNotes" class="field" rows="3" placeholder="Descreva as mudanças..." />
        </div>

        <label v-if="authStore.isAdmin" class="checkbox-row">
          <input type="checkbox" v-model="createMeta.isProvisioning" />
          Firmware de provisionamento (usado no flash inicial dos devices)
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

    <!-- ── Gerar Pacote Flash modal ─────────────────────────────────────────── -->
    <div v-if="showPackage" class="modal-overlay" @click.self="showPackage = false">
      <div class="modal">
        <h3 class="modal-title">Gerar Pacote Flash</h3>

        <!-- Estado 1: nenhum firmware de provisioning cadastrado -->
        <div v-if="!provisioningFirmware" class="prov-warning">
          <svg class="warn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
          <div>
            <p class="warn-title">Sem firmware de provisionamento registrado</p>
            <p class="warn-desc" v-if="authStore.isAdmin">É necessário ter um firmware marcado como provisioning para gerar o pacote flash.</p>
            <p class="warn-desc" v-else>É necessário ter um firmware marcado como provisioning. Contate um administrador para registrar um.</p>
          </div>
        </div>

        <!-- Estado 2: existe provisioning, mas versão mais recente está DEPRECATED -->
        <div v-else-if="provisioningLatestDeprecated" class="prov-warning">
          <svg class="warn-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/>
            <line x1="12" y1="9" x2="12" y2="13"/><line x1="12" y1="17" x2="12.01" y2="17"/>
          </svg>
          <div>
            <p class="warn-title">Versão de provisionamento está obsoleta</p>
            <p class="warn-desc">
              A versão mais recente do firmware <strong>{{ provisioningFirmware.firmwareName }}</strong>
              (<span class="mono">v{{ provisioningFirmware.latestVersion?.version }}</span>) foi marcada como DEPRECATED
              e não pode ser usada para provisionar novos dispositivos.
              Contate um administrador para subir uma versão corrigida.
            </p>
          </div>
        </div>

        <!-- Estado 3: provisioning OK -->
        <div v-else class="prov-ok">
          <svg class="ok-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
          <div>
            <p class="ok-title">Firmware de provisionamento</p>
            <p class="ok-desc">
              <span class="mono">{{ provisioningFirmware.firmwareName }}</span>
              <span class="mono text-muted" v-if="provisioningFirmware.latestVersion"> v{{ provisioningFirmware.latestVersion.version }}</span>
            </p>
          </div>
        </div>

        <template v-if="provisioningReady">
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
              <input v-model="packageForm.wifiPass" :type="showWifiPass ? 'text' : 'password'"
                class="field pass-field" placeholder="••••••••" autocomplete="new-password" />
              <button class="pass-toggle" @click="showWifiPass = !showWifiPass" type="button">
                {{ showWifiPass ? 'Ocultar' : 'Mostrar' }}
              </button>
            </div>
          </div>
          <div class="form-group">
            <label>Grupo <span class="text-muted">(opcional)</span></label>
            <select v-model="packageForm.groupId" class="field">
              <option value="">Nenhum</option>
              <option v-for="g in groups" :key="g.groupId" :value="g.groupId">{{ g.name }}</option>
            </select>
          </div>
        </template>

        <p v-if="generateError" class="field-error">{{ generateError }}</p>
        <div class="modal-footer" :style="!provisioningFirmware && authStore.isAdmin ? 'flex-direction: row-reverse; justify-content: flex-start; gap: 100px' : ''">
          <AppButton v-if="!provisioningFirmware && authStore.isAdmin" variant="primary" @click="openCreateFromPackage">
            Registrar Firmware de Provisionamento
          </AppButton>
          <div style="display:flex;gap:8px">
            <AppButton variant="ghost" @click="showPackage = false">Cancelar</AppButton>
            <AppButton v-if="provisioningReady" variant="primary" :loading="generating" @click="doGenerate">
              Gerar e Baixar
            </AppButton>
          </div>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.actions-row { display: flex; gap: var(--space-2); }
.ml-1 { margin-left: var(--space-1); }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); font-size: var(--text-sm); }
.tbl-row { cursor: pointer; transition: background var(--transition); }
.tbl-row:hover td { background: var(--panel); }
.desc-cell { max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; color: var(--text); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.text-danger { color: var(--danger); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }

.owner-platform { font-size: var(--text-xs); background: var(--primary-dim, rgba(99,102,241,.1)); color: var(--primary); border-radius: var(--radius-sm); padding: 1px 6px; font-weight: 500; }
.owner-group { font-size: var(--text-xs); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-sm); padding: 1px 6px; }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 520px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow-y: auto; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }

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

.sensor-select-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 200px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2); }
.sensor-select-row { display: flex; align-items: center; gap: var(--space-2); }
.pin-wrapper { display: flex; align-items: center; gap: var(--space-1); flex-shrink: 0; }
.pin-label { font-size: var(--text-sm); color: var(--text-muted); white-space: nowrap; }
.pin-field { width: 60px; padding: 4px 8px; resize: none; }

.prov-warning { display: flex; gap: var(--space-3); padding: var(--space-4); background: rgba(239,68,68,.08); border: 1px solid rgba(239,68,68,.25); border-radius: var(--radius-md); }
.warn-icon { width: 20px; height: 20px; color: var(--danger); flex-shrink: 0; margin-top: 2px; }
.warn-title { font-size: var(--text-sm); font-weight: 600; color: var(--danger); margin: 0 0 4px; }
.warn-desc { font-size: var(--text-xs); color: var(--text-muted); margin: 0; line-height: 1.5; }
.prov-ok { display: flex; gap: var(--space-3); padding: var(--space-3) var(--space-4); background: rgba(16,185,129,.08); border: 1px solid rgba(16,185,129,.25); border-radius: var(--radius-md); align-items: center; }
.ok-icon { width: 18px; height: 18px; color: var(--success); flex-shrink: 0; }
.ok-title { font-size: var(--text-xs); color: var(--text-muted); margin: 0; }
.ok-desc { display: flex; align-items: center; gap: var(--space-2); margin: 4px 0 0; font-size: var(--text-sm); font-weight: 500; color: var(--text); }
</style>
