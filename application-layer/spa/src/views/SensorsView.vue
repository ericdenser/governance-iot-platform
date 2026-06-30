<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import AppModal from '@/components/AppModal.vue'
import { sensorsApi } from '@/services/sensors'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const sensors = ref<any[]>([])
const loading = ref(true)
const showForm = ref(false)
const newName = ref('')
const saving = ref(false)
const error = ref('')

const load = async () => { const r = await sensorsApi.list(); sensors.value = r.data }

const save = async () => {
  if (!newName.value.trim()) return
  saving.value = true; error.value = ''
  try {
    await sensorsApi.register(newName.value.trim())
    showForm.value = false; newName.value = ''; await load()
  } catch (e: any) {
    error.value = e.response?.data?.message ?? 'Erro ao cadastrar sensor.'
  } finally { saving.value = false }
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Sensores" description="Tipos de sensor reconhecidos pelo sistema">
      <template #actions>
        <AppButton v-if="authStore.isAdmin" size="lg" variant="primary" @click="showForm = true">
          Registrar Sensor
        </AppButton>
      </template>

      <div v-if="loading" class="empty">Carregando...</div>
      <table v-else class="tbl">
        <thead><tr><th>Nome</th><th>ID</th><th>Criado por</th></tr></thead>
        <tbody>
          <tr v-for="s in sensors" :key="s.sensorId">
            <td class="font-medium">{{ s.name }}</td>
            <td class="mono text-muted text-sm">{{ s.sensorId }}</td>
            <td class="text-sm">{{ s.createdByUsername ?? '—' }}</td>
          </tr>
          <tr v-if="!sensors.length"><td colspan="3" class="empty">Nenhum sensor cadastrado</td></tr>
        </tbody>
      </table>
    </AppCard>

    <AppModal title="Registrar Sensor" :show="showForm" @close="showForm = false">
      <div class="form-group">
        <label>Nome do sensor</label>
        <input v-model="newName" class="field" placeholder="ex: Temperatura, Umidade..." @keydown.enter="save" />
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showForm = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="saving" @click="save">Salvar</AppButton>
      </template>
    </AppModal>
  </AppLayout>
</template>

<style scoped>
.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 0 var(--space-3); text-align: left; }
.tbl td { padding: var(--space-3) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; width: 100%; box-sizing: border-box; }
.field:focus { border-color: var(--primary); }
.error { color: var(--danger); font-size: var(--text-sm); margin: 0; }
</style>
