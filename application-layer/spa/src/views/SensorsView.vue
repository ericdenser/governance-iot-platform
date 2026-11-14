<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import AppModal from '@/components/AppModal.vue'
import { sensorsApi } from '@/services/sensors'
import { useAuthStore } from '@/stores/auth'
import type { SensorResponseDTO } from '@/types/models'
import { errorMessage } from '@/utils/errors'
import { toast } from '@/composables/useToast'

// Same rule as the govApi (ValidationPatterns.SENSOR_CODE_REGEX)
const CODE_RE = /^[a-z0-9_]{1,24}$/

const authStore = useAuthStore()
const sensors = ref<SensorResponseDTO[]>([])
const loading = ref(true)

const load = async () => {
  const r = await sensorsApi.list()
  sensors.value = (Array.isArray(r.data) ? r.data : []).sort((a, b) => a.code.localeCompare(b.code))
}

// Register
const showForm = ref(false)
const newCode = ref('')
const newName = ref('')
const saving = ref(false)
const error = ref('')
const codeValid = computed(() => CODE_RE.test(newCode.value))

const openForm = () => { newCode.value = ''; newName.value = ''; error.value = ''; showForm.value = true }

const save = async () => {
  if (!codeValid.value) { error.value = 'Código inválido.'; return }
  saving.value = true; error.value = ''
  try {
    await sensorsApi.register(newCode.value, newName.value.trim() || undefined)
    showForm.value = false; await load()
    toast.success('Sensor cadastrado')
  } catch (e: unknown) {
    error.value = errorMessage(e, 'Erro ao cadastrar sensor.')
  } finally { saving.value = false }
}

// Rename
const renameTarget = ref<SensorResponseDTO | null>(null)
const renameValue = ref('')
const renaming = ref(false)
const renameError = ref('')

const openRename = (s: SensorResponseDTO) => {
  renameTarget.value = s; renameValue.value = s.name; renameError.value = ''
}

const doRename = async () => {
  if (!renameTarget.value || !renameValue.value.trim()) return
  renaming.value = true; renameError.value = ''
  try {
    await sensorsApi.rename(renameTarget.value.sensorId, renameValue.value.trim())
    renameTarget.value = null; await load()
    toast.success('Nome atualizado')
  } catch (e: unknown) {
    renameError.value = errorMessage(e, 'Erro ao renomear sensor.')
  } finally { renaming.value = false }
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard>
      <template #actions>
        <AppButton v-if="authStore.isAdmin" size="lg" variant="primary" @click="openForm">
          Registrar Sensor
        </AppButton>
      </template>

      <p class="hint text-sm text-muted">
        Sensores reportados pelos dispositivos entram aqui automaticamente, com o código como nome.
        O código precisa ser o mesmo que o firmware envia.
      </p>

      <AppSkeleton v-if="loading" variant="table" />
      <table v-else class="tbl">
        <thead><tr><th>Código</th><th>Nome</th><th>Criado por</th><th></th></tr></thead>
        <tbody>
          <tr v-for="s in sensors" :key="s.sensorId">
            <td class="mono text-sm">{{ s.code }}</td>
            <td class="font-medium">{{ s.name }}</td>
            <td class="text-sm">{{ s.createdByUsername ?? 'Automático' }}</td>
            <td class="col-actions">
              <AppButton v-if="authStore.isAdmin" size="sm" variant="ghost" @click="openRename(s)">Renomear</AppButton>
            </td>
          </tr>
          <tr v-if="!sensors.length"><td colspan="4">
              <EmptyState title="Nenhum sensor cadastrado"
                          hint="Eles aparecem sozinhos quando um dispositivo reporta um sensor ou quando um firmware com manifesto é enviado.">
                <AppButton v-if="authStore.isAdmin" variant="secondary" @click="openForm">Registrar Sensor</AppButton>
              </EmptyState>
            </td></tr>
        </tbody>
      </table>
    </AppCard>

    <AppModal title="Registrar Sensor" :show="showForm" @close="showForm = false">
      <div class="form-group">
        <label for="sensors-f1">Código <span class="text-muted">(igual ao do firmware)</span></label>
        <input id="sensors-f1" v-model="newCode" class="field mono" placeholder="ex: bmp280" maxlength="24" @keydown.enter="save" />
        <small v-if="newCode && !codeValid" class="error">Use de 1 a 24 caracteres: a-z, 0-9 e _</small>
      </div>
      <div class="form-group">
        <label for="sensors-f2">Nome <span class="text-muted">(opcional)</span></label>
        <input id="sensors-f2" v-model="newName" class="field" placeholder="ex: Pressão e temperatura" maxlength="60" @keydown.enter="save" />
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showForm = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="saving" :disabled="!codeValid" @click="save">Salvar</AppButton>
      </template>
    </AppModal>

    <AppModal title="Renomear Sensor" :show="!!renameTarget" @close="renameTarget = null">
      <p v-if="renameTarget" class="text-sm text-muted">Código: <span class="mono">{{ renameTarget.code }}</span></p>
      <div class="form-group">
        <label for="sensors-f3">Nome</label>
        <input id="sensors-f3" v-model="renameValue" class="field" maxlength="60" @keydown.enter="doRename" />
      </div>
      <p v-if="renameError" class="error">{{ renameError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="renameTarget = null">Cancelar</AppButton>
        <AppButton variant="primary" :loading="renaming" :disabled="!renameValue.trim()" @click="doRename">Salvar</AppButton>
      </template>
    </AppModal>
  </AppLayout>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: 0 0 var(--space-3); text-align: left; }
.tbl td { padding: var(--space-3) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.error { color: var(--danger); font-size: var(--text-sm); margin: 0; }
.hint { margin: 0 0 var(--space-4); }
.col-actions { text-align: right; }
</style>
