<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { firmwareApi } from '@/services/firmware'
import { devicesApi } from '@/services/devices'
import { errorMessage } from '@/utils/errors'
import { toast } from '@/composables/useToast'
import { chipLabel, chipOf } from '@/utils/chips'
import type { DeviceSummaryDTO, TargetChip } from '@/types/models'

const props = defineProps<{
  versionId: string
  version: string
  targetChip: TargetChip | null | undefined
}>()

const emit = defineEmits<{ (e: 'close' | 'deployed'): void }>()

const allDevices = ref<DeviceSummaryDTO[]>([])
const loading = ref(true)
const selected = ref<Set<string>>(new Set())
const deploying = ref(false)
const error = ref('')

// Backend also refuses a device of another chip
const deployable = computed(() => {
  const chip = chipOf(props.targetChip)
  return allDevices.value.filter(d => d.status !== 'REVOKED' && chipOf(d.chip) === chip)
})

const toggle = (id: string) => {
  const next = new Set(selected.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selected.value = next
}

const deploy = async () => {
  if (!selected.value.size) { error.value = 'Selecione ao menos um device.'; return }
  deploying.value = true; error.value = ''
  try {
    await firmwareApi.deploy(props.versionId, [...selected.value])
    toast.success(`Deploy v${props.version} enviado para ${selected.value.size} device(s)`)
    emit('deployed')
  } catch (e: unknown) {
    error.value = errorMessage(e, 'Erro ao enviar deploy.')
  } finally { deploying.value = false }
}

onMounted(async () => {
  try { allDevices.value = await devicesApi.listAll() } finally { loading.value = false }
})
</script>

<template>
  <AppModal size="md" @close="emit('close')">
    <template #title>Deploy — <span class="mono">v{{ version }}</span></template>
    <p class="modal-desc">Selecione os dispositivos que receberão o update via OTA.</p>

    <AppSkeleton v-if="loading" variant="list" :rows="3" />
    <EmptyState v-else-if="!deployable.length" compact
      :title="`Nenhum dispositivo ${chipLabel(targetChip)} disponível`"
      hint="O deploy só vai para dispositivos do mesmo hardware do firmware e que não estejam revogados." />
    <div v-else class="device-grid">
      <button v-for="d in deployable" :key="d.deviceId" type="button"
        class="device-chip" :class="{ selected: selected.has(d.deviceId) }"
        :aria-pressed="selected.has(d.deviceId)"
        @click="toggle(d.deviceId)">
        <span class="chip-name">{{ d.name }}</span>
        <span class="chip-ver">{{ d.firmwareVersion ? `v${d.firmwareVersion}` : '—' }}</span>
        <StatusLabel kind="device" :code="d.status" label="" />
      </button>
    </div>

    <p v-if="error" class="field-error">{{ error }}</p>
    <template #footer>
      <span class="text-muted text-sm">{{ selected.size }} selecionado(s)</span>
      <AppButton variant="ghost" @click="emit('close')">Cancelar</AppButton>
      <AppButton variant="primary" :loading="deploying" @click="deploy">Enviar OTA</AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.modal-desc { font-size: var(--text-sm); color: var(--text-muted); margin: 0; }
.device-grid { display: flex; flex-wrap: wrap; gap: var(--space-2); overflow-y: auto; max-height: 280px; align-content: flex-start; }
.device-chip { display: flex; align-items: center; gap: var(--space-2); background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2) var(--space-3); cursor: pointer; transition: border-color var(--transition), background var(--transition); }
.device-chip:hover { border-color: var(--primary); }
.device-chip.selected { border-color: var(--primary); background: var(--primary-dim); }
.chip-name { font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.chip-ver { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); }
</style>
