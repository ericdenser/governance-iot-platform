<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { firmwareApi } from '@/services/firmware'
import { devicesApi } from '@/services/devices'
import { absoluteTime, formatBytes } from '@/utils/format'
import type { DeviceSummaryDTO, FirmwareVersionResponseDTO, FirmwareVersionSummaryDTO } from '@/types/models'

const props = defineProps<{
  summary: FirmwareVersionSummaryDTO
  canManage: boolean
  canDelete: boolean
  canDeploy: boolean
  reuploading: boolean
  groupName: (ownerGroupId: string | null) => string
}>()

const emit = defineEmits<{
  (e: 'close' | 'deploy' | 'deprecate' | 'reactivate' | 'delete' | 'reupload'): void
}>()

const version = ref<FirmwareVersionResponseDTO | null>(null)
const devices = ref<DeviceSummaryDTO[]>([])
const loadingDevices = ref(true)

onMounted(async () => {
  try {
    const [vRes, all] = await Promise.all([
      firmwareApi.getVersion(props.summary.versionId),
      devicesApi.listAll(),
    ])
    version.value = vRes.data
    devices.value = all.filter(d => d.firmwareVersionId === props.summary.versionId)
  } finally { loadingDevices.value = false }
})
</script>

<template>
  <AppModal size="lg" @close="emit('close')">
    <template #title>{{ version ? (version.originalFilename ?? version.filename) : `Versão v${summary.version}` }}</template>

    <AppSkeleton v-if="!version" variant="list" :rows="6" />
    <template v-else>
      <div class="detail-sub">
        <span class="mono">v{{ version.version }}</span>
        <StatusLabel kind="firmware" :code="version.status" no-icon />
        <span v-if="version.provisioningFirmware">Provisioning</span>
        <span :class="{ 'text-muted': !version.ownerGroupId }">{{ groupName(version.ownerGroupId) }}</span>
      </div>

      <div class="detail-grid">
        <div class="detail-item">
          <span class="detail-label">Tamanho</span>
          <span class="detail-value">{{ formatBytes(version.sizeBytes) }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Deploys confirmados</span>
          <span class="detail-value">{{ version.deployCount }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Enviado por</span>
          <span class="detail-value">{{ version.createdByUsername ?? '—' }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Data de upload</span>
          <span class="detail-value">{{ absoluteTime(version.uploadedAt) }}</span>
        </div>
        <div class="detail-item">
          <span class="detail-label">Deep sleep</span>
          <span class="detail-value">
            {{ version.deepSleepEnabled ? `Sim (${version.deepSleepIntervalS}s)` : 'Não' }}
          </span>
        </div>
        <div class="detail-item detail-full">
          <span class="detail-label">SHA-256</span>
          <span class="detail-value mono text-xs sha">{{ version.sha256 }}</span>
        </div>
        <div v-if="version.releaseNotes" class="detail-item detail-full">
          <span class="detail-label">Release Notes</span>
          <span class="detail-value text-sm pre">{{ version.releaseNotes }}</span>
        </div>
      </div>

      <div v-if="version.sensorConfigs?.length" class="detail-section">
        <p class="section-title">Sensores suportados</p>
        <div class="tbl-scroll">
          <table class="sensor-tbl">
            <thead><tr><th>Sensor</th><th>Ligação (pino do sensor → GPIO)</th><th>Métricas</th></tr></thead>
            <tbody>
              <tr v-for="s in version.sensorConfigs" :key="s.sensorCode">
                <td>
                  <span class="sensor-name">{{ s.sensorName }}</span>
                  <span v-if="s.sensorName !== s.sensorCode" class="mono text-muted text-xs sensor-code">{{ s.sensorCode }}</span>
                </td>
                <td class="mono text-sm">
                  <template v-if="Object.keys(s.pins).length">
                    <span v-for="(gpio, pin) in s.pins" :key="pin" class="pin-pair">{{ pin }} → GPIO{{ gpio }}</span>
                  </template>
                  <span v-else class="text-muted">interno</span>
                </td>
                <td class="text-xs text-muted">{{ s.metrics.length ? s.metrics.join(', ') : '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="detail-section">
        <p class="section-title">Dispositivos nesta versão</p>
        <AppSkeleton v-if="loadingDevices" variant="list" :rows="1" />
        <div v-else-if="!devices.length" class="empty-sm">Nenhum dispositivo nesta versão.</div>
        <div v-else class="device-pills">
          <span v-for="d in devices" :key="d.deviceId" class="device-pill">{{ d.name }}</span>
        </div>
      </div>
    </template>

    <template #footer>
      <div v-if="canManage" class="footer-left">
        <AppButton v-if="summary.status === 'CORRUPTED'" variant="secondary"
          :loading="reuploading" @click="emit('reupload')">Reenviar binário</AppButton>
        <AppButton v-if="summary.status !== 'DEPRECATED'" variant="danger-ghost"
          @click="emit('deprecate')">Deprecar</AppButton>
        <AppButton v-if="summary.status === 'DEPRECATED'" variant="secondary"
          @click="emit('reactivate')">Reativar</AppButton>
        <AppButton v-if="summary.status === 'DEPRECATED' && canDelete" variant="danger-ghost"
          @click="emit('delete')">Excluir versão</AppButton>
      </div>
      <AppButton variant="ghost" @click="emit('close')">Fechar</AppButton>
      <AppButton v-if="canManage && canDeploy" variant="primary" @click="emit('deploy')">Deploy</AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.detail-sub { display: flex; align-items: center; flex-wrap: wrap; margin-top: calc(var(--space-3) * -1); font-size: var(--text-sm); color: var(--text-secondary); }
.detail-sub > * + *::before { content: '·'; margin: 0 var(--space-2); color: var(--text-muted); }
.detail-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: var(--space-3); }
.detail-full { grid-column: 1 / -1; }
.detail-item { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.detail-label { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); }
.detail-value { font-size: var(--text-sm); color: var(--text); }
.sha { overflow-wrap: anywhere; }
.pre { white-space: pre-wrap; line-height: 1.5; }
.detail-section { display: flex; flex-direction: column; gap: var(--space-2); }
.section-title { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); margin: 0; }
.tbl-scroll { overflow-x: auto; }
.sensor-tbl { width: 100%; border-collapse: collapse; }
.sensor-tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); text-align: left; padding: 0 12px var(--space-2) 0; }
.sensor-tbl td { padding: var(--space-2) 12px var(--space-2) 0; border-top: 1px solid var(--border); vertical-align: top; }
.sensor-name { font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.sensor-code { margin-left: 6px; }
.pin-pair { display: inline-block; margin-right: var(--space-3); white-space: nowrap; }
.device-pills { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.device-pill { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 2px var(--space-2); font-size: var(--text-xs); font-family: var(--font-mono); color: var(--text); }
.empty-sm { color: var(--text-muted); font-size: var(--text-sm); padding: var(--space-2) 0; }
.footer-left { display: flex; flex-wrap: wrap; gap: var(--space-2); margin-right: auto; }
</style>
