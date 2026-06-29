<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import { firmwareApi } from '@/services/firmware'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const firmwares = ref<any[]>([])
const loading = ref(true)

// Upload modal
const showUpload = ref(false)
const uploadFile = ref<File | null>(null)
const uploadMeta = ref({ version: '', releaseNotes: '', isProvisioning: false })
const uploading = ref(false)
const uploadError = ref('')

// Generate package modal
const showPackage = ref(false)
const packageName = ref('')
const generating = ref(false)

// Deploy modal
const showDeploy = ref(false)
const deployFirmwareId = ref('')
const deployDeviceIds = ref('')
const deploying = ref(false)

const statusVariant = (s: string): any => ({ STAGED: 'muted', DEPLOYED: 'success', DEPRECATED: 'danger' }[s] ?? 'muted')
const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'
const bytes = (n: number) => n > 1024 * 1024 ? `${(n / 1024 / 1024).toFixed(1)} MB` : `${(n / 1024).toFixed(0)} KB`

const load = async () => {
  const r = await firmwareApi.list(); firmwares.value = r.data
}

const doUpload = async () => {
  if (!uploadFile.value || !uploadMeta.value.version) { uploadError.value = 'Arquivo e versão são obrigatórios.'; return }
  uploading.value = true; uploadError.value = ''
  try {
    await firmwareApi.upload(uploadFile.value, uploadMeta.value)
    showUpload.value = false
    uploadFile.value = null
    uploadMeta.value = { version: '', releaseNotes: '', isProvisioning: false }
    await load()
  } catch (e: any) {
    uploadError.value = e.response?.data?.message ?? 'Erro ao fazer upload.'
  } finally { uploading.value = false }
}

const doDeprecate = async (id: string) => {
  if (!confirm('Marcar firmware como DEPRECATED?')) return
  await firmwareApi.deprecate(id); await load()
}

const doSetProvisioning = async (id: string) => {
  if (!confirm('Definir como firmware de provisionamento?')) return
  await firmwareApi.setProvisioning(id); await load()
}

const doDeploy = async () => {
  if (!deployDeviceIds.value.trim()) return
  deploying.value = true
  try {
    const targets = deployDeviceIds.value.split(',').map(s => s.trim()).filter(Boolean)
    await firmwareApi.deploy(deployFirmwareId.value, targets)
    showDeploy.value = false; deployDeviceIds.value = ''
  } finally { deploying.value = false }
}

const doGenerate = async () => {
  if (!packageName.value.trim()) return
  generating.value = true
  try {
    const r = await firmwareApi.generatePackage({ deviceName: packageName.value })
    const url = URL.createObjectURL(r.data)
    const a = document.createElement('a')
    a.href = url; a.download = `flash_package_${packageName.value}.zip`; a.click()
    URL.revokeObjectURL(url)
    showPackage.value = false; packageName.value = ''
  } finally { generating.value = false }
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Firmware">
      <template #actions>
        <div class="actions-row" v-if="authStore.isAdmin">
          <AppButton size="sm" variant="secondary" @click="showPackage = true">Gerar Pacote Flash</AppButton>
          <AppButton size="sm" variant="primary" @click="showUpload = true">+ Upload Firmware</AppButton>
        </div>
      </template>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Versão</th><th>Status</th><th>Tamanho</th><th>Provisioning</th>
            <th>Enviado por</th><th>Data upload</th><th v-if="authStore.isAdmin">Ações</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="fw in firmwares" :key="fw.firmwareId">
            <td class="mono font-medium">v{{ fw.version }}</td>
            <td><AppBadge :variant="statusVariant(fw.status)">{{ fw.status }}</AppBadge></td>
            <td class="text-muted text-sm">{{ bytes(fw.sizeBytes) }}</td>
            <td><AppBadge v-if="fw.provisioningFirmware" variant="primary" dot>Sim</AppBadge><span v-else class="text-muted">—</span></td>
            <td class="text-sm">{{ fw.createdByUsername ?? '—' }}</td>
            <td class="text-muted text-sm">{{ fmt(fw.uploadedAt) }}</td>
            <td v-if="authStore.isAdmin">
              <div class="row-actions">
                <AppButton size="sm" variant="secondary"
                           :disabled="fw.status === 'DEPRECATED'"
                           @click="deployFirmwareId = fw.firmwareId; showDeploy = true">
                  Deploy
                </AppButton>
                <AppButton size="sm" variant="ghost"
                           :disabled="fw.provisioningFirmware || fw.status === 'DEPRECATED'"
                           @click="doSetProvisioning(fw.firmwareId)">
                  Provisioning
                </AppButton>
                <AppButton size="sm" variant="danger"
                           :disabled="fw.status === 'DEPRECATED'"
                           @click="doDeprecate(fw.firmwareId)">
                  Deprecar
                </AppButton>
              </div>
            </td>
          </tr>
          <tr v-if="!firmwares.length"><td colspan="7" class="empty">Nenhum firmware cadastrado</td></tr>
        </tbody>
      </table>
    </AppCard>

    <!-- Upload modal -->
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
        <p v-if="uploadError" class="error">{{ uploadError }}</p>
        <div class="modal-footer">
          <AppButton variant="ghost" @click="showUpload = false">Cancelar</AppButton>
          <AppButton variant="primary" :loading="uploading" @click="doUpload">Enviar</AppButton>
        </div>
      </div>
    </div>

    <!-- Deploy modal -->
    <div v-if="showDeploy" class="modal-overlay" @click.self="showDeploy = false">
      <div class="modal">
        <h3 class="modal-title">Deploy de Firmware</h3>
        <div class="form-group">
          <label>Device IDs alvo (separados por vírgula)</label>
          <textarea v-model="deployDeviceIds" class="field" rows="4" placeholder="abc-123, def-456..." />
        </div>
        <div class="modal-footer">
          <AppButton variant="ghost" @click="showDeploy = false">Cancelar</AppButton>
          <AppButton variant="primary" :loading="deploying" @click="doDeploy">Enviar Comando</AppButton>
        </div>
      </div>
    </div>

    <!-- Generate package modal -->
    <div v-if="showPackage" class="modal-overlay" @click.self="showPackage = false">
      <div class="modal">
        <h3 class="modal-title">Gerar Pacote Flash</h3>
        <p class="modal-desc">Gera um arquivo .zip com todos os binários e o token de provisionamento para um novo dispositivo.</p>
        <div class="form-group">
          <label>Nome do dispositivo</label>
          <input v-model="packageName" class="field" placeholder="ex: sensor-armazem-01" />
        </div>
        <div class="modal-footer">
          <AppButton variant="ghost" @click="showPackage = false">Cancelar</AppButton>
          <AppButton variant="primary" :loading="generating" @click="doGenerate">Gerar e Baixar</AppButton>
        </div>
      </div>
    </div>
  </AppLayout>
</template>

<style scoped>
.actions-row { display: flex; gap: var(--space-2); }
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.row-actions { display: flex; gap: var(--space-2); }

.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 480px; max-width: 90vw; display: flex; flex-direction: column; gap: var(--space-4); }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-desc { font-size: var(--text-sm); color: var(--text-muted); margin: 0; }
.modal-footer { display: flex; justify-content: flex-end; gap: var(--space-2); margin-top: var(--space-2); }
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; resize: vertical; }
.field:focus { border-color: var(--primary); }
.checkbox-row { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); cursor: pointer; }
.error { color: var(--danger); font-size: var(--text-sm); margin: 0; }
</style>
