<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import AppModal from '@/components/AppModal.vue'
import { groupsApi } from '@/services/groups'

const groups = ref<any[]>([])
const loading = ref(true)

// Selected group detail panel
const selectedGroup = ref<any>(null)
const groupDevices = ref<any[]>([])
const groupUsers = ref<any[]>([])
const loadingDetail = ref(false)

// Create group modal
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', description: '' })
const createError = ref('')

// Add device modal
const showAddDevice = ref(false)
const addDeviceId = ref('')
const addingDevice = ref(false)
const addDeviceError = ref('')

// Assign user modal
const showAssignUser = ref(false)
const assignForm = ref({ keycloakUserId: '', role: 'MEMBER' })
const assigningUser = ref(false)
const assignError = ref('')

const ROLES = ['OWNER', 'MEMBER', 'VIEWER']

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const load = async () => {
  const r = await groupsApi.list()
  groups.value = r.data ?? []
}

const selectGroup = async (g: any) => {
  selectedGroup.value = g
  loadingDetail.value = true
  try {
    const [devRes, userRes] = await Promise.all([
      groupsApi.listDevices(g.groupId),
      groupsApi.listUsers(g.groupId),
    ])
    groupDevices.value = devRes.data ?? []
    groupUsers.value = userRes.data ?? []
  } finally { loadingDetail.value = false }
}

const doCreate = async () => {
  if (!createForm.value.name.trim()) { createError.value = 'Nome é obrigatório.'; return }
  creating.value = true; createError.value = ''
  try {
    await groupsApi.create({ name: createForm.value.name.trim(), description: createForm.value.description.trim() || undefined })
    showCreate.value = false; createForm.value = { name: '', description: '' }
    await load()
  } catch (e: any) {
    createError.value = e.response?.data?.message ?? 'Erro ao criar grupo.'
  } finally { creating.value = false }
}

const doDelete = async (g: any) => {
  if (!confirm(`Excluir grupo "${g.name}"? Todos os membros serão removidos.`)) return
  await groupsApi.delete(g.groupId)
  if (selectedGroup.value?.groupId === g.groupId) selectedGroup.value = null
  await load()
}

const doAddDevice = async () => {
  if (!addDeviceId.value.trim()) { addDeviceError.value = 'Informe o Device ID.'; return }
  addingDevice.value = true; addDeviceError.value = ''
  try {
    await groupsApi.addDevice(selectedGroup.value.groupId, addDeviceId.value.trim())
    showAddDevice.value = false; addDeviceId.value = ''
    await selectGroup(selectedGroup.value)
  } catch (e: any) {
    addDeviceError.value = e.response?.data?.message ?? 'Erro ao adicionar dispositivo.'
  } finally { addingDevice.value = false }
}

const doRemoveDevice = async (deviceId: string) => {
  if (!confirm('Remover dispositivo do grupo?')) return
  await groupsApi.removeDevice(selectedGroup.value.groupId, deviceId)
  await selectGroup(selectedGroup.value)
}

const doAssignUser = async () => {
  if (!assignForm.value.keycloakUserId.trim()) { assignError.value = 'Informe o ID do usuário.'; return }
  assigningUser.value = true; assignError.value = ''
  try {
    await groupsApi.assignUser(selectedGroup.value.groupId, {
      keycloakUserId: assignForm.value.keycloakUserId.trim(),
      role: assignForm.value.role,
    })
    showAssignUser.value = false; assignForm.value = { keycloakUserId: '', role: 'MEMBER' }
    await selectGroup(selectedGroup.value)
  } catch (e: any) {
    assignError.value = e.response?.data?.message ?? 'Erro ao atribuir usuário.'
  } finally { assigningUser.value = false }
}

const doRemoveUser = async (userId: string) => {
  if (!confirm('Remover usuário do grupo?')) return
  await groupsApi.removeUser(selectedGroup.value.groupId, userId)
  await selectGroup(selectedGroup.value)
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <div class="groups-grid">
      <!-- Group list -->
      <AppCard title="Grupos">
        <template #actions>
          <AppButton size="sm" variant="primary" @click="showCreate = true">+ Novo Grupo</AppButton>
        </template>

        <div v-if="loading" class="empty">Carregando...</div>
        <ul v-else class="group-list">
          <li
            v-for="g in groups" :key="g.groupId"
            class="group-item"
            :class="{ active: selectedGroup?.groupId === g.groupId }"
            @click="selectGroup(g)"
          >
            <div class="group-info">
              <span class="group-name">{{ g.name }}</span>
              <span class="group-desc text-muted text-xs" v-if="g.description">{{ g.description }}</span>
            </div>
            <AppButton
              size="sm" variant="danger"
              @click.stop="doDelete(g)"
            >Excluir</AppButton>
          </li>
          <li v-if="!groups.length" class="empty">Nenhum grupo cadastrado</li>
        </ul>
      </AppCard>

      <!-- Group detail -->
      <div v-if="selectedGroup" class="detail-col">
        <AppCard :title="selectedGroup.name">
          <template #actions>
            <AppButton size="sm" variant="secondary" @click="showAddDevice = true">+ Dispositivo</AppButton>
            <AppButton size="sm" variant="secondary" @click="showAssignUser = true">+ Usuário</AppButton>
          </template>

          <div v-if="loadingDetail" class="empty">Carregando...</div>
          <div v-else class="detail-sections">
            <!-- Devices -->
            <div class="section">
              <h4 class="section-title">Dispositivos ({{ groupDevices.length }})</h4>
              <table class="tbl">
                <thead><tr><th>Nome</th><th>Device ID</th><th>Adicionado por</th><th></th></tr></thead>
                <tbody>
                  <tr v-for="d in groupDevices" :key="d.deviceId ?? d.id">
                    <td class="text-sm font-medium">{{ d.name ?? d.deviceId }}</td>
                    <td class="mono text-xs text-muted">{{ d.deviceId }}</td>
                    <td class="text-xs text-muted">{{ d.addedByUsername ?? '—' }}</td>
                    <td>
                      <AppButton size="sm" variant="ghost" @click="doRemoveDevice(d.deviceId)">Remover</AppButton>
                    </td>
                  </tr>
                  <tr v-if="!groupDevices.length"><td colspan="4" class="empty">Nenhum dispositivo</td></tr>
                </tbody>
              </table>
            </div>

            <!-- Users -->
            <div class="section">
              <h4 class="section-title">Usuários ({{ groupUsers.length }})</h4>
              <table class="tbl">
                <thead><tr><th>Keycloak ID</th><th>Papel</th><th>Atribuído por</th><th></th></tr></thead>
                <tbody>
                  <tr v-for="u in groupUsers" :key="u.keycloakUserId ?? u.id">
                    <td class="mono text-xs">{{ u.keycloakUserId }}</td>
                    <td class="text-sm">{{ u.role }}</td>
                    <td class="text-xs text-muted">{{ u.assignedByUsername ?? '—' }}</td>
                    <td>
                      <AppButton size="sm" variant="ghost" @click="doRemoveUser(u.keycloakUserId)">Remover</AppButton>
                    </td>
                  </tr>
                  <tr v-if="!groupUsers.length"><td colspan="4" class="empty">Nenhum usuário</td></tr>
                </tbody>
              </table>
            </div>
          </div>
        </AppCard>
      </div>
      <div v-else class="detail-col">
        <AppCard>
          <div class="placeholder">
            <span class="placeholder-inner">Selecione um grupo para ver detalhes</span>
          </div>
        </AppCard>
      </div>
    </div>

    <AppModal title="Novo Grupo" :show="showCreate" @close="showCreate = false">
      <div class="form-group">
        <label>Nome</label>
        <input v-model="createForm.name" class="field" placeholder="ex: Armazém Norte" @keydown.enter="doCreate" />
      </div>
      <div class="form-group">
        <label>Descrição <span class="text-muted">(opcional)</span></label>
        <input v-model="createForm.description" class="field" placeholder="Descrição do grupo..." />
      </div>
      <p v-if="createError" class="error">{{ createError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showCreate = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="creating" @click="doCreate">Criar</AppButton>
      </template>
    </AppModal>

    <AppModal title="Adicionar Dispositivo" :show="showAddDevice" @close="showAddDevice = false">
      <div class="form-group">
        <label>Device ID</label>
        <input v-model="addDeviceId" class="field" placeholder="UUID do dispositivo" @keydown.enter="doAddDevice" />
      </div>
      <p v-if="addDeviceError" class="error">{{ addDeviceError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showAddDevice = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="addingDevice" @click="doAddDevice">Adicionar</AppButton>
      </template>
    </AppModal>

    <AppModal title="Atribuir Usuário" :show="showAssignUser" @close="showAssignUser = false">
      <div class="form-group">
        <label>Keycloak User ID (UUID)</label>
        <input v-model="assignForm.keycloakUserId" class="field" placeholder="UUID do usuário no Keycloak" />
      </div>
      <div class="form-group">
        <label>Papel</label>
        <select v-model="assignForm.role" class="field">
          <option v-for="r in ROLES" :key="r" :value="r">{{ r }}</option>
        </select>
      </div>
      <p v-if="assignError" class="error">{{ assignError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showAssignUser = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="assigningUser" @click="doAssignUser">Atribuir</AppButton>
      </template>
    </AppModal>
  </AppLayout>
</template>

<style scoped>
.groups-grid { display: grid; grid-template-columns: 320px 1fr; width: 100%; gap: var(--space-4); align-items: start; }
@media (max-width: 900px) { .groups-grid { grid-template-columns: 1fr; } }

.group-list { list-style: none; display: flex; flex-direction: column; gap: 0; }
.group-item { display: flex; align-items: center; justify-content: space-between; padding: var(--space-3) var(--space-2); border-bottom: 1px solid var(--border); cursor: pointer; border-radius: var(--radius-md); transition: background var(--transition); gap: var(--space-2); }
.group-item:last-child { border-bottom: none; }
.group-item:hover { background: var(--panel); }
.group-item.active { background: var(--panel); outline: 1px solid var(--primary); }
.group-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.group-name { font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.group-desc { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.detail-col { min-width: 0; }
.placeholder { display: flex; align-items: center; justify-content: center; min-height: 160px; }
.placeholder-inner { color: var(--text-muted); font-size: var(--text-sm); }

.detail-sections { display: flex; flex-direction: column; gap: var(--space-6); }
.section {}
.section-title { font-size: var(--text-sm); font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: .5px; margin: 0 0 var(--space-3); }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-2) 12px var(--space-2) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.font-medium { font-weight: 500; }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-6) 0; font-size: var(--text-sm); }

.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; width: 100%; box-sizing: border-box; }
.field:focus { border-color: var(--primary); }
.error { color: var(--danger); font-size: var(--text-sm); margin: 0; }
</style>
