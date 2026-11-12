<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import { firmwareApi } from '@/services/firmware'
import { sensorsApi } from '@/services/sensors'
import { errorMessage } from '@/utils/errors'
import type { SensorResponseDTO, UploadVersionRequest } from '@/types/models'

const props = defineProps<{
  firmwareId: string
  firmwareName: string
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'uploaded', version: string): void
}>()

const file = ref<File | null>(null)
const meta = ref({ version: '', releaseNotes: '', deepSleepEnabled: false, deepSleepIntervalS: 300 })
const uploading = ref(false)
const error = ref('')
const availableSensors = ref<SensorResponseDTO[]>([])
const selectedSensors = ref<Set<string>>(new Set())

const toggleSensor = (sensorId: string) => {
  const next = new Set(selectedSensors.value)
  if (next.has(sensorId)) next.delete(sensorId); else next.add(sensorId)
  selectedSensors.value = next
}

const upload = async () => {
  if (!file.value || !meta.value.version.trim()) {
    error.value = 'Arquivo e versão são obrigatórios.'
    return
  }
  if (meta.value.deepSleepEnabled && (!meta.value.deepSleepIntervalS || meta.value.deepSleepIntervalS < 30)) {
    error.value = 'Intervalo de deep sleep deve ser >= 30 segundos.'
    return
  }
  uploading.value = true; error.value = ''
  try {
    const payload: UploadVersionRequest = {
      version: meta.value.version.trim(),
      releaseNotes: meta.value.releaseNotes || null,
      deepSleepEnabled: meta.value.deepSleepEnabled,
      deepSleepIntervalS: meta.value.deepSleepEnabled ? meta.value.deepSleepIntervalS : null,
      sensors: [...selectedSensors.value].map(sensorId => ({ sensorId })),
    }
    await firmwareApi.uploadVersion(props.firmwareId, file.value, payload as unknown as Record<string, unknown>)
    emit('uploaded', payload.version)
  } catch (e: unknown) {
    error.value = errorMessage(e, 'Erro ao subir nova versão.')
  } finally { uploading.value = false }
}

onMounted(async () => {
  const r = await sensorsApi.list()
  availableSensors.value = Array.isArray(r.data) ? r.data : []
})
</script>

<template>
  <AppModal size="md" :title="`Nova Versão de ${firmwareName}`" @close="emit('close')">
    <div class="form-group">
      <label for="firmwaredetail-f1">Arquivo .bin</label>
      <input id="firmwaredetail-f1" type="file" accept=".bin" @change="e => file = (e.target as HTMLInputElement).files?.[0] ?? null" />
    </div>

    <div class="form-group">
      <label for="firmwaredetail-f2">Versão</label>
      <input id="firmwaredetail-f2" v-model="meta.version" class="field" placeholder="ex: 1.0.3" />
    </div>

    <div class="form-group">
      <label for="firmwaredetail-f3">Release Notes</label>
      <textarea id="firmwaredetail-f3" v-model="meta.releaseNotes" class="field" rows="3" placeholder="Descreva as mudanças..." />
    </div>

    <label class="checkbox-row">
      <input v-model="meta.deepSleepEnabled" type="checkbox" />
      Firmware usa deep sleep
    </label>

    <div v-if="meta.deepSleepEnabled" class="form-group">
      <label for="firmwaredetail-f4">Intervalo de deep sleep (segundos)</label>
      <input id="firmwaredetail-f4" v-model.number="meta.deepSleepIntervalS" type="number" min="30" class="field" placeholder="ex: 300" />
      <small class="text-muted">Timeout de comando pra devices nessa versão: intervalo × 2 + 60s.</small>
    </div>

    <div v-if="availableSensors.length" class="form-group">
      <span class="group-label">Sensores <span class="text-muted">(só para binários sem manifesto)</span></span>
      <small class="text-muted">Firmwares com os plugins de sensor trazem os sensores e pinos no próprio binário; esta lista é ignorada.</small>
      <div class="sensor-select-list">
        <label v-for="s in availableSensors" :key="s.sensorId" class="checkbox-row">
          <input type="checkbox" :checked="selectedSensors.has(s.sensorId)" @change="toggleSensor(s.sensorId)" />
          {{ s.name }} <span v-if="s.name !== s.code" class="text-muted mono">{{ s.code }}</span>
        </label>
      </div>
    </div>

    <p v-if="error" class="field-error">{{ error }}</p>
    <template #footer>
      <AppButton variant="ghost" @click="emit('close')">Cancelar</AppButton>
      <AppButton variant="primary" :loading="uploading" @click="upload">Enviar</AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label, .group-label { font-size: var(--text-sm); color: var(--text-muted); }
.checkbox-row { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); cursor: pointer; }
.sensor-select-list { display: flex; flex-direction: column; gap: var(--space-2); max-height: 200px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-2); }
.sensor-select-list .checkbox-row { color: var(--text); }
</style>
