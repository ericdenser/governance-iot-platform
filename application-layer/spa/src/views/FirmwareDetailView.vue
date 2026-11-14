<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import AppButton from '@/components/AppButton.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import VersionDetailModal from '@/components/firmware/VersionDetailModal.vue'
import VersionUploadModal from '@/components/firmware/VersionUploadModal.vue'
import VersionDeployModal from '@/components/firmware/VersionDeployModal.vue'
import { firmwareApi } from '@/services/firmware'
import { groupsApi } from '@/services/groups'
import { useAuthStore } from '@/stores/auth'
import type {
  FirmwareResponseDTO,
  FirmwareVersionSummaryDTO,
  DeviceGroupResponseDTO,
} from '@/types/models'
import { errorMessage } from '@/utils/errors'
import { confirm } from '@/composables/useConfirm'
import { toast } from '@/composables/useToast'
import { chipLabel } from '@/utils/chips'
import { absoluteTime, formatBytes } from '@/utils/format'

const authStore = useAuthStore()
const route = useRoute()
const router = useRouter()

const firmwareId = route.params.firmwareId as string

const firmware = ref<FirmwareResponseDTO | null>(null)
const versions = ref<FirmwareVersionSummaryDTO[]>([])
const groups = ref<DeviceGroupResponseDTO[]>([])
const loading = ref(true)

const fmt = absoluteTime
const fmtDate = (iso: string | null | undefined) => iso ? new Date(iso).toLocaleDateString('pt-BR') : '—'
const bytes = formatBytes

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

// Delete exige OWNER do grupo dono (ou admin) — MEMBER não basta
const canDelete = computed(() => {
  if (!firmware.value) return false
  if (authStore.isAdmin) return true
  if (!firmware.value.ownerGroupId) return false
  return groups.value.find(g => g.groupId === firmware.value!.ownerGroupId)?.myRole === 'OWNER'
})

const load = async () => {
  const [fwRes, vRes] = await Promise.all([
    firmwareApi.get(firmwareId),
    firmwareApi.listVersions(firmwareId),
  ])
  firmware.value = fwRes.data
  versions.value = Array.isArray(vRes.data) ? vRes.data :  []
}

// ── Modals (each one loads its own data) ─────────────────────────────────────
const detailSummary = ref<FirmwareVersionSummaryDTO | null>(null)
const showUpload = ref(false)
const deployTarget = ref<FirmwareVersionSummaryDTO | null>(null)

const openDeploy = (v: FirmwareVersionSummaryDTO) => {
  detailSummary.value = null
  deployTarget.value = v
}

const onUploaded = async (version: string) => {
  showUpload.value = false
  await load()
  toast.success(`Versão v${version} publicada`)
}

// ── Deprecate version ────────────────────────────────────────────────────────
const doDeprecate = async (v: FirmwareVersionSummaryDTO) => {
  detailSummary.value = null
  const ok = await confirm({
    title: `Deprecar versão v${v.version}?`,
    message: 'A versão não poderá mais receber deploy até ser reativada.',
    confirmText: 'Deprecar',
    danger: true,
  })
  if (!ok) return
  try {
    await firmwareApi.deprecate(v.versionId); await load()
    toast.success(`Versão v${v.version} deprecada`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao deprecar versão.'))
  }
}

// ── Reactivate version ───────────────────────────────────────────────────────
const doReactivate = async (v: FirmwareVersionSummaryDTO) => {
  detailSummary.value = null
  const ok = await confirm({
    title: `Reativar versão v${v.version}?`,
    message: 'Ela volta a aceitar deploy, inclusive em devices que já fizeram rollback dela.',
    confirmText: 'Reativar',
  })
  if (!ok) return
  try {
    await firmwareApi.reactivate(v.versionId); await load()
    toast.success(`Versão v${v.version} reativada`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao reativar versão.'))
  }
}

// ── Reupload binary (versão CORRUPTED) ───────────────────────────────────────
const reuploadInput = ref<HTMLInputElement | null>(null)
const reuploadTarget = ref<FirmwareVersionSummaryDTO | null>(null)
const reuploadingId = ref<string | null>(null)

const openReupload = (v: FirmwareVersionSummaryDTO) => {
  detailSummary.value = null
  reuploadTarget.value = v
  reuploadInput.value?.click()
}

const onReuploadFile = async (e: Event) => {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0] ?? null
  input.value = ''
  const v = reuploadTarget.value
  if (!file || !v) return
  reuploadingId.value = v.versionId
  try {
    await firmwareApi.reuploadBinary(v.versionId, file)
    await load()
    toast.success(`Binário da v${v.version} restaurado`)
  } catch (err: unknown) {
    toast.error(errorMessage(err, 'Erro ao reenviar binário.'))
  } finally {
    reuploadingId.value = null
    reuploadTarget.value = null
  }
}

// ── Delete version / firmware ────────────────────────────────────────────────
const doDeleteVersion = async (v: FirmwareVersionSummaryDTO) => {
  detailSummary.value = null
  const ok = await confirm({
    title: `Excluir versão v${v.version}?`,
    message: 'Isso deletará também o binário do storage. Ação irreversível.',
    confirmText: 'Excluir',
    danger: true,
  })
  if (!ok) return
  try {
    await firmwareApi.deleteVersion(v.versionId); await load()
    toast.success(`Versão v${v.version} excluída`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao excluir versão.'))
  }
}

const canDeploy = (v: FirmwareVersionSummaryDTO) => v.status !== 'DEPRECATED' && v.status !== 'CORRUPTED'

const doDeleteFirmware = async () => {
  if (!firmware.value) return
  const ok = await confirm({
    title: `Excluir firmware "${firmware.value.firmwareName}"?`,
    message: 'Todas as versões e binários serão removidos do storage. Ação irreversível.',
    confirmText: 'Excluir',
    danger: true,
  })
  if (!ok) return
  try {
    await firmwareApi.deleteFirmware(firmwareId)
    toast.success(`Firmware "${firmware.value.firmwareName}" excluído`)
    router.push('/firmware')
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao excluir firmware.'))
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
    <AppSkeleton v-if="loading" variant="detail" />

    <div v-else-if="firmware" class="detail">
      <!-- Header -->
      <div class="detail-header">
        <div>
          <button class="back-btn" @click="router.push('/firmware')">← Firmware</button>
          <h1 class="firmware-name">{{ firmware.firmwareName }}</h1>
          <!-- Plain meta line, items separated by dots -->
          <p class="firmware-meta">
            <span :class="{ 'text-muted': !firmware.ownerGroupId }">{{ groupName(firmware.ownerGroupId) }}</span>
            <span v-if="firmware.provisioningFirmware">Provisioning</span>
            <span class="mono">{{ chipLabel(firmware.targetChip) }}</span>
            <span>criado por {{ firmware.createdByUsername ?? '—' }} em {{ fmt(firmware.createdAt) }}</span>
          </p>
          <p v-if="firmware.description" class="firmware-desc">{{ firmware.description }}</p>
        </div>
        <div class="header-actions">
          <AppButton v-if="canDelete" variant="danger-ghost" @click="doDeleteFirmware">Excluir firmware</AppButton>
          <AppButton v-if="canManage" variant="primary" @click="showUpload = true">Nova versão</AppButton>
        </div>
      </div>

      <section>
        <h3 class="block-title">Versões <span class="count">{{ versions.length }}</span></h3>
        <AppCard no-padding>
          <table class="tbl">
            <thead>
              <tr>
                <th>Versão</th>
                <th>Status</th>
                <th>Deploys</th>
                <th>Tamanho</th>
                <th>Enviado por</th>
                <th>Data</th>
                <th v-if="canManage"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="v in versions" :key="v.versionId" class="tbl-row" tabindex="0" role="button" @click="detailSummary = v" @keydown.enter.self="detailSummary = v">
                <td class="mono font-medium">v{{ v.version }}</td>
                <td><StatusLabel kind="firmware" :code="v.status" no-icon /></td>
                <td>{{ v.deployCount }}</td>
                <td class="text-muted">{{ bytes(v.sizeBytes) }}</td>
                <td>{{ v.createdByUsername ?? '—' }}</td>
                <td class="text-muted" :title="fmt(v.uploadedAt)">{{ fmtDate(v.uploadedAt) }}</td>
                <!-- Other version actions live in the version modal -->
                <td v-if="canManage" class="cell-action" @click.stop>
                  <AppButton v-if="canDeploy(v)" size="sm" variant="secondary" @click="openDeploy(v)">Deploy</AppButton>
                </td>
              </tr>
              <tr v-if="!versions.length">
                <td :colspan="canManage ? 7 : 6">
                  <EmptyState title="Nenhuma versão registrada"
                              :hint="canManage ? 'Envie o primeiro binário para poder fazer deploy.' : 'Quem gerencia este firmware ainda não enviou um binário.'">
                    <AppButton v-if="canManage" variant="primary" @click="showUpload = true">Nova versão</AppButton>
                  </EmptyState>
                </td>
              </tr>
            </tbody>
          </table>
        </AppCard>
      </section>

      <input ref="reuploadInput" type="file" accept=".bin" style="display:none" @change="onReuploadFile" />
    </div>

    <VersionDetailModal v-if="detailSummary"
      :summary="detailSummary" :can-manage="!!canManage" :can-delete="canDelete"
      :can-deploy="canDeploy(detailSummary)" :reuploading="reuploadingId === detailSummary.versionId"
      :group-name="groupName"
      @close="detailSummary = null"
      @deploy="openDeploy(detailSummary)"
      @deprecate="doDeprecate(detailSummary)"
      @reactivate="doReactivate(detailSummary)"
      @delete="doDeleteVersion(detailSummary)"
      @reupload="openReupload(detailSummary)" />

    <VersionUploadModal v-if="showUpload && firmware"
      :firmware-id="firmwareId" :firmware-name="firmware.firmwareName"
      @close="showUpload = false" @uploaded="onUploaded" />

    <VersionDeployModal v-if="deployTarget"
      :version-id="deployTarget.versionId" :version="deployTarget.version" :target-chip="firmware?.targetChip"
      @close="deployTarget = null" @deployed="deployTarget = null" />
  </AppLayout>
</template>

<style scoped>
.detail { display: flex; flex-direction: column; gap: var(--space-4); }
.detail-header { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-4); flex-wrap: wrap; }
.header-actions { display: flex; gap: var(--space-2); }
.back-btn { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: var(--text-sm); margin-bottom: var(--space-2); padding: 0; }
.back-btn:hover { color: var(--text); }
.firmware-name { font-size: var(--text-xl); font-weight: 600; color: var(--text); margin: 0 0 var(--space-1); }
.firmware-meta { display: flex; align-items: center; flex-wrap: wrap; margin: 0; font-size: var(--text-sm); color: var(--text-secondary); }
.firmware-meta > span + span::before { content: '·'; margin: 0 var(--space-2); color: var(--text-muted); }
.firmware-desc { color: var(--text-muted); font-size: var(--text-sm); margin: var(--space-2) 0 0; }


.block-title { display: flex; align-items: baseline; gap: var(--space-2); font-size: var(--text-sm); font-weight: 600; color: var(--text-secondary); margin: 0 0 var(--space-2); }
.count { font-weight: 400; color: var(--text-muted); font-family: var(--font-mono); font-size: var(--text-xs); }
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: var(--space-3) var(--space-4); text-align: left; white-space: nowrap; }
.tbl td { padding: var(--space-3) var(--space-4); border-top: 1px solid var(--border); font-size: var(--text-sm); }
.cell-action { text-align: right; width: 1%; white-space: nowrap; }
.tbl-row { cursor: pointer; transition: background var(--transition); }
.tbl-row:hover td { background: var(--panel); }

.font-medium { font-weight: 500; color: var(--text); }
</style>
