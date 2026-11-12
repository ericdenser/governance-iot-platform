<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { firmwareApi } from '@/services/firmware'
import { absoluteTime, relativeTime } from '@/utils/format'
import { chipLabel, chipOf } from '@/utils/chips'
import type { DeployableVersionProjection, DeviceSummaryDTO, TargetChip } from '@/types/models'

const props = defineProps<{
  chip: TargetChip | null
  targets: DeviceSummaryDTO[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'confirm', version: DeployableVersionProjection): void
}>()

const versions = ref<DeployableVersionProjection[]>([])
const loading = ref(true)
const picked = ref<DeployableVersionProjection | null>(null)

// Firmwares, each with its versions (newest first)
const groups = computed(() => {
  const byId = new Map<string, { firmwareId: string; firmwareName: string; versions: DeployableVersionProjection[] }>()
  for (const fw of versions.value) {
    let g = byId.get(fw.firmwareId)
    if (!g) { g = { firmwareId: fw.firmwareId, firmwareName: fw.firmwareName, versions: [] }; byId.set(fw.firmwareId, g) }
    g.versions.push(fw)
  }
  const list = [...byId.values()]
  for (const g of list) g.versions.sort((a, b) => (b.uploadedAt ?? '').localeCompare(a.uploadedAt ?? ''))
  return list.sort((a, b) => a.firmwareName.localeCompare(b.firmwareName))
})

const alreadyOnVersion = computed(() =>
  picked.value ? props.targets.filter(d => d.firmwareVersionId === picked.value!.versionId).length : 0)

onMounted(async () => {
  try {
    const r = await firmwareApi.listDeployable()
    const all = Array.isArray(r.data) ? r.data : []
    versions.value = all.filter(fw => chipOf(fw.targetChip) === props.chip)
  } finally { loading.value = false }
})
</script>

<template>
  <AppModal size="md" @close="emit('close')">
    <template #title>Escolher Firmware — UPDATE <span class="text-muted mono">{{ chipLabel(chip) }}</span></template>
    <p class="modal-hint mono">{{ targets.length }} device(s) alvo</p>

    <AppSkeleton v-if="loading" variant="list" :rows="4" />
    <EmptyState v-else-if="!versions.length" compact
      :title="`Nenhum firmware disponível para ${chipLabel(chip)}`"
      hint="Cadastre um firmware desse hardware ou reative uma versão na tela de Firmware.">
      <AppButton variant="secondary" @click="$router.push('/firmware')">Abrir Firmware</AppButton>
    </EmptyState>
    <div v-else class="fw-list">
      <section v-for="g in groups" :key="g.firmwareId" class="fw-group">
        <header class="fw-group-head">
          <span class="fw-name">{{ g.firmwareName }}</span>
          <span class="fw-group-count mono">{{ g.versions.length }} {{ g.versions.length === 1 ? 'versão' : 'versões' }}</span>
        </header>
        <button v-for="fw in g.versions" :key="fw.versionId" type="button"
          class="fw-row" :class="{ selected: picked?.versionId === fw.versionId }"
          :aria-pressed="picked?.versionId === fw.versionId"
          @click="picked = fw">
          <span class="fw-version mono">v{{ fw.version }}</span>
          <span class="fw-date" :title="absoluteTime(fw.uploadedAt)">{{ relativeTime(fw.uploadedAt) }}</span>
          <StatusLabel kind="firmware" :code="fw.status" />
        </button>
      </section>
    </div>

    <p v-if="alreadyOnVersion > 0" class="modal-hint mono warn">
      {{ alreadyOnVersion }} device(s) selecionados já rodam essa versão — serão ignorados pelo backend.
    </p>

    <template #footer>
      <AppButton variant="ghost" @click="emit('close')">Cancelar</AppButton>
      <AppButton variant="primary" :disabled="!picked" @click="picked && emit('confirm', picked)">Enviar UPDATE</AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.modal-hint { font-size: var(--text-xs); color: var(--text-muted); margin: 0; }
.modal-hint.warn { color: var(--warning); }
.fw-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 320px; overflow-y: auto; }
.fw-row { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); cursor: pointer; text-align: left; transition: border-color var(--transition); color: inherit; }
.fw-row:hover { border-color: var(--primary); }
.fw-row.selected { border-color: var(--accent-border); background: var(--accent-dim); }
.fw-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.fw-version { font-size: var(--text-sm); color: var(--text); flex: 1; }
.fw-date { font-size: var(--text-xs); color: var(--text-muted); }
.fw-group { display: flex; flex-direction: column; gap: var(--space-1); }
.fw-group + .fw-group { margin-top: var(--space-3); }
.fw-group-head { display: flex; align-items: baseline; justify-content: space-between; gap: var(--space-2); padding: 0 var(--space-1) var(--space-1); }
.fw-group-count { font-size: var(--text-xs); color: var(--text-muted); }
</style>
