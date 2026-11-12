<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusLabel from '@/components/StatusLabel.vue'
import { groupsApi } from '@/services/groups'
import { devicesApi } from '@/services/devices'
import { errorMessage } from '@/utils/errors'
import { chipLabel } from '@/utils/chips'
import type { DeviceSummaryDTO } from '@/types/models'

const props = defineProps<{
  groupId: string
  excludeIds: Set<string>
}>()

const emit = defineEmits<{ (e: 'close' | 'added'): void }>()

const devices = ref<DeviceSummaryDTO[]>([])
const loading = ref(true)
const search = ref('')
const selectedId = ref('')
const adding = ref(false)
const error = ref('')

const pickable = computed(() => {
  const q = search.value.toLowerCase()
  return devices.value
    .filter(d => d.status !== 'REVOKED' && !props.excludeIds.has(d.deviceId))
    .filter(d => !q || d.name?.toLowerCase().includes(q) || d.deviceId?.toLowerCase().includes(q))
})

const add = async () => {
  if (!selectedId.value) { error.value = 'Selecione um dispositivo.'; return }
  adding.value = true; error.value = ''
  try {
    await groupsApi.addDevice(props.groupId, selectedId.value)
    emit('added')
  } catch (e: unknown) {
    error.value = errorMessage(e, 'Erro ao adicionar dispositivo.')
  } finally { adding.value = false }
}

onMounted(async () => {
  try { devices.value = await devicesApi.listAll() } finally { loading.value = false }
})
</script>

<template>
  <AppModal title="Adicionar Dispositivo" @close="emit('close')">
    <input v-model="search" class="field" placeholder="Buscar por nome ou ID..." aria-label="Buscar dispositivo" />
    <AppSkeleton v-if="loading" variant="list" :rows="4" />
    <EmptyState v-else-if="!pickable.length" compact
      :title="devices.length ? 'Nenhum dispositivo disponível para adicionar' : 'Nenhum dispositivo cadastrado'"
      :hint="devices.length ? 'Os que você pode ver já estão neste grupo ou foram revogados.' : 'Provisione um dispositivo primeiro.'" />
    <div v-else class="picker">
      <button
        v-for="d in pickable" :key="d.deviceId" type="button"
        class="picker-row" :class="{ selected: selectedId === d.deviceId }"
        :aria-pressed="selectedId === d.deviceId"
        @click="selectedId = d.deviceId"
      >
        <span class="picker-name">{{ d.name }}</span>
        <span class="picker-id">{{ chipLabel(d.chip) }} · {{ d.deviceId.slice(0, 8) }}…</span>
        <StatusLabel kind="device" :code="d.status" label="" />
      </button>
    </div>
    <p v-if="error" class="field-error">{{ error }}</p>
    <template #footer>
      <AppButton variant="ghost" @click="emit('close')">Cancelar</AppButton>
      <AppButton variant="primary" :loading="adding" :disabled="!selectedId" @click="add">Adicionar</AppButton>
    </template>
  </AppModal>
</template>

<style scoped src="./picker.css"></style>
