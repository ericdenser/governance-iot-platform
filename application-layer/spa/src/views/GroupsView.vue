<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppButton from '@/components/AppButton.vue'
import AppModal from '@/components/AppModal.vue'
import { groupsApi } from '@/services/groups'
import { devicesApi } from '@/services/devices'
import { usersApi } from '@/services/users'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()

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
const addingDevice = ref(false)
const addDeviceError = ref('')
const allDevices = ref<any[]>([])
const loadingAllDevices = ref(false)
const deviceSearch = ref('')
const selectedDeviceId = ref('')

const statusVariant = (s: string): any =>
  ({ ACTIVE: 'success', PENDING: 'warning', PROVISIONING: 'info', COMMAND_PENDING: 'info', REVOKED: 'danger' }[s] ?? 'muted')

const alreadyInGroup = computed(() => new Set(groupDevices.value.map((d: any) => d.deviceId)))

const pickableDevices = computed(() => {
  const inGroup = alreadyInGroup.value
  const q = deviceSearch.value.toLowerCase()
  return allDevices.value
    .filter(d => d.status !== 'REVOKED' && !inGroup.has(d.deviceId))
    .filter(d => !q || d.name?.toLowerCase().includes(q) || d.deviceId?.toLowerCase().includes(q))
})

const openAddDevice = async () => {
  selectedDeviceId.value = ''
  deviceSearch.value = ''
  addDeviceError.value = ''
  showAddDevice.value = true
  if (!allDevices.value.length) {
    loadingAllDevices.value = true
    try {
      const r = await devicesApi.list()
      allDevices.value = Array.isArray(r.data) ? r.data : (r.data?.content ?? [])
    } finally { loadingAllDevices.value = false }
  }
}

// Assign user modal
const showAssignUser = ref(false)
const assigningUser = ref(false)
const assignError = ref('')
const allUsers = ref<any[]>([])
const loadingAllUsers = ref(false)
const userSearch = ref('')
const selectedUserId = ref('')
const selectedRole = ref('MEMBER')

const alreadyAssigned = computed(() => new Set(groupUsers.value.map((u: any) => u.keycloakUserId)))

const pickableUsers = computed(() => {
  const assigned = alreadyAssigned.value
  const q = userSearch.value.toLowerCase()
  return allUsers.value
    .filter(u => !assigned.has(u.keycloakUserId))
    .filter(u => !q || u.username?.toLowerCase().includes(q) || u.email?.toLowerCase().includes(q))
})

const usernameFor = (id: string) =>
  allUsers.value.find(u => u.keycloakUserId === id)?.username ?? null

const openAssignUser = async () => {
  selectedUserId.value = ''
  userSearch.value = ''
  selectedRole.value = 'MEMBER'
  assignError.value = ''
  showAssignUser.value = true
  if (!allUsers.value.length) {
    loadingAllUsers.value = true
    try {
      allUsers.value = (await usersApi.list()).data ?? []
    } finally { loadingAllUsers.value = false }
  }
}

// Update role modal
const showUpdateRole = ref(false)
const updatingRole = ref(false)
const updateRoleTarget = ref<any>(null)
const newRole = ref('')
const updateRoleError = ref('')

const openUpdateRole = (u: any) => {
  updateRoleTarget.value = u
  newRole.value = u.role
  updateRoleError.value = ''
  showUpdateRole.value = true
}

const doUpdateRole = async () => {
  if (!newRole.value) return
  updatingRole.value = true; updateRoleError.value = ''
  try {
    await groupsApi.updateRole(selectedGroup.value.groupId, updateRoleTarget.value.keycloakUserId, { role: newRole.value })
    showUpdateRole.value = false
    await selectGroup(selectedGroup.value)
  } catch (e: any) {
    updateRoleError.value = e.response?.data?.message ?? 'Erro ao atualizar papel.'
  } finally { updatingRole.value = false }
}

// Role-based permissions
const myGroupRole = computed(() => {
  if (!authStore.keycloakUserId || !selectedGroup.value) return null
  return groupUsers.value.find((u: any) => u.keycloakUserId === authStore.keycloakUserId)?.role ?? null
})

const canManageDevices = computed(() =>
  authStore.isAdmin || myGroupRole.value === 'MEMBER' || myGroupRole.value === 'OWNER'
)

const canManageUsers = computed(() =>
  authStore.isAdmin || myGroupRole.value === 'OWNER'
)

const assignableRoles = computed(() =>
  authStore.isAdmin ? ['OWNER', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER']
)

const updateRoleOptions = computed(() =>
  authStore.isAdmin ? ['OWNER', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER']
)

const canUpdateRole = (u: any) => {
  if (authStore.isAdmin) return true
  if (myGroupRole.value === 'OWNER' && u.role !== 'OWNER') return true
  return false
}

const fmt = (iso: string) => iso ? new Date(iso).toLocaleString('pt-BR') : '—'

const load = async () => {
  const r = await groupsApi.list()
  groups.value = r.data ?? []
}

const selectGroup = async (g: any) => {
  selectedGroup.value = g
  loadingDetail.value = true
  try {
    const calls: Promise<any>[] = [
      groupsApi.listDevices(g.groupId),
      groupsApi.listUsers(g.groupId),
    ]
    if (!allUsers.value.length) calls.push(usersApi.list())
    const [devRes, userRes, usersRes] = await Promise.all(calls)
    groupDevices.value = devRes.data ?? []
    groupUsers.value = userRes.data ?? []
    if (usersRes) allUsers.value = usersRes.data ?? []
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
  if (!selectedDeviceId.value) { addDeviceError.value = 'Selecione um dispositivo.'; return }
  addingDevice.value = true; addDeviceError.value = ''
  try {
    await groupsApi.addDevice(selectedGroup.value.groupId, selectedDeviceId.value)
    showAddDevice.value = false; selectedDeviceId.value = ''
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
  if (!selectedUserId.value) { assignError.value = 'Selecione um usuário.'; return }
  assigningUser.value = true; assignError.value = ''
  try {
    await groupsApi.assignUser(selectedGroup.value.groupId, {
      keycloakUserId: selectedUserId.value,
      role: selectedRole.value,
    })
    showAssignUser.value = false; selectedUserId.value = ''
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
          <AppButton v-if="authStore.isAdmin" size="md" variant="primary" @click="showCreate = true">Novo Grupo</AppButton>
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
              v-if="authStore.isAdmin"
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
            <div style="display: flex; gap: var(--space-2);">
              <AppButton v-if="canManageDevices" size="md" variant="secondary" @click="openAddDevice">Adicionar Dispositivo</AppButton>
              <AppButton v-if="canManageUsers" size="md" variant="secondary" @click="openAssignUser">Adicionar Usuário</AppButton>
            </div>
          </template>

          <div v-if="loadingDetail" class="empty">Carregando...</div>
          <div v-else class="detail-sections">
            <!-- Devices -->
            <div class="section">
              <h4 class="section-title">Dispositivos ({{ groupDevices.length }})</h4>
              <table class="tbl">
                <thead>
                  <tr>
                    <th style="width: 40%;">Dispositivo</th>
                    <th style="width: 20%;">Atribuído por</th>
                    <th v-if="canManageDevices" style="width: 12%; text-align: right;"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="d in groupDevices" :key="d.deviceId">
                    <td>
                      <div class="cell-main">{{ d.name }}</div>
                      <div class="mono text-xs text-muted">{{ d.deviceId }}</div>
                    </td>
                    <td class="text-xs text-muted">{{ d.addedByUsername ?? '—' }}</td>
                    <td v-if="canManageDevices" style="text-align: right;">
                      <AppButton size="sm" variant="ghost" @click="doRemoveDevice(d.deviceId)">Remover</AppButton>
                    </td>
                  </tr>
                  <tr v-if="!groupDevices.length">
                    <td :colspan="canManageDevices ? 3 : 2" class="empty">Nenhum dispositivo</td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- Users -->
            <div class="section">
              <h4 class="section-title">Usuários ({{ groupUsers.length }})</h4>
              <table class="tbl">
                <thead>
                  <tr>
                    <th style="width: 35%;">Usuário</th>
                    <th style="width: 15%;">Papel</th>
                    <th style="width: 25%;">Atribuído por</th>
                    <th style="text-align: right;"></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="u in groupUsers" :key="u.keycloakUserId">
                    <td>
                      <div class="cell-main">{{ usernameFor(u.keycloakUserId) ?? u.keycloakUserId }}</div>
                      <div v-if="usernameFor(u.keycloakUserId)" class="mono text-xs text-muted">{{ u.keycloakUserId }}</div>
                    </td>
                    <td class="text-sm">{{ u.role }}</td>
                    <td class="text-xs text-muted">{{ u.assignedByUsername ?? '—' }}</td>
                    <td style="text-align: right;">
                      <div style="display: flex; gap: var(--space-1); justify-content: flex-end;">
                        <AppButton v-if="canUpdateRole(u)" size="sm" variant="secondary" @click="openUpdateRole(u)">Atualizar Papel</AppButton>
                        <AppButton v-if="canManageUsers" size="sm" variant="ghost" @click="doRemoveUser(u.keycloakUserId)">Remover</AppButton>
                      </div>
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
      <input v-model="deviceSearch" class="field" placeholder="Buscar por nome ou ID..." />
      <div v-if="loadingAllDevices" class="picker-empty">Carregando dispositivos...</div>
      <div v-else-if="!pickableDevices.length" class="picker-empty text-muted">
        {{ allDevices.length ? 'Nenhum dispositivo disponível para adicionar.' : 'Nenhum dispositivo cadastrado.' }}
      </div>
      <div v-else class="device-picker">
        <button
          v-for="d in pickableDevices" :key="d.deviceId"
          class="picker-chip" :class="{ selected: selectedDeviceId === d.deviceId }"
          @click="selectedDeviceId = d.deviceId"
        >
          <span class="picker-name">{{ d.name }}</span>
          <span class="picker-id">{{ d.deviceId.slice(0, 8) }}…</span>
          <AppBadge :variant="statusVariant(d.status)" dot />
        </button>
      </div>
      <p v-if="addDeviceError" class="error">{{ addDeviceError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showAddDevice = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="addingDevice" :disabled="!selectedDeviceId" @click="doAddDevice">Adicionar</AppButton>
      </template>
    </AppModal>

    <AppModal title="Atribuir Usuário" :show="showAssignUser" @close="showAssignUser = false">
      <input v-model="userSearch" class="field" placeholder="Buscar por nome ou e-mail..." />
      <div v-if="loadingAllUsers" class="picker-empty">Carregando usuários...</div>
      <div v-else-if="!pickableUsers.length" class="picker-empty text-muted">
        {{ allUsers.length ? 'Todos os usuários já estão no grupo.' : 'Nenhum usuário encontrado.' }}
      </div>
      <div v-else class="device-picker">
        <button
          v-for="u in pickableUsers" :key="u.keycloakUserId"
          class="picker-chip" :class="{ selected: selectedUserId === u.keycloakUserId }"
          @click="selectedUserId = u.keycloakUserId"
        >
          <span class="picker-name">{{ u.username }}</span>
          <span class="picker-id">{{ u.email }}</span>
        </button>
      </div>
      <div class="form-group">
        <label>Papel</label>
        <select v-model="selectedRole" class="field">
          <option v-for="r in assignableRoles" :key="r" :value="r">{{ r }}</option>
        </select>
      </div>
      <p v-if="assignError" class="error">{{ assignError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showAssignUser = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="assigningUser" :disabled="!selectedUserId" @click="doAssignUser">Atribuir</AppButton>
      </template>
    </AppModal>

    <AppModal title="Atualizar Papel" :show="showUpdateRole" @close="showUpdateRole = false">
      <p class="text-sm text-muted" v-if="updateRoleTarget">
        Usuário: <strong>{{ usernameFor(updateRoleTarget.keycloakUserId) ?? updateRoleTarget.keycloakUserId }}</strong>
      </p>
      <div class="form-group">
        <label>Novo Papel</label>
        <select v-model="newRole" class="field">
          <option v-for="r in updateRoleOptions" :key="r" :value="r">{{ r }}</option>
        </select>
      </div>
      <p v-if="updateRoleError" class="error">{{ updateRoleError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showUpdateRole = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="updatingRole" @click="doUpdateRole">Salvar</AppButton>
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

.detail-sections { display: flex; flex-direction: column; gap: var(--space-10); }
.section {}
.section-title { font-size: var(--text-muted); font-weight: 600; color: var(--text-sm); text-transform: uppercase; letter-spacing: .5px; margin: 0 0 var(--space-10); }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-2) 12px var(--space-2) 0; border-top: 1px solid var(--border); }
.mono { font-family: var(--font-mono); }
.cell-main { font-size: var(--text-sm); font-weight: 500; color: var(--text); }
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

.device-picker { display: flex; flex-direction: column; gap: var(--space-1); max-height: 260px; overflow-y: auto; border: 1px solid var(--border); border-radius: var(--radius-md); padding: var(--space-1); }
.picker-chip { display: flex; align-items: center; gap: var(--space-2); background: none; border: none; border-radius: var(--radius-sm); padding: var(--space-2) var(--space-3); cursor: pointer; text-align: left; transition: background var(--transition); width: 100%; }
.picker-chip:hover { background: var(--panel); }
.picker-chip.selected { background: var(--primary-dim); outline: 1px solid var(--primary); outline-offset: -1px; }
.picker-name { font-size: var(--text-sm); font-weight: 500; color: var(--text); flex: 1; }
.picker-id { font-family: var(--font-mono); font-size: var(--text-xs); color: var(--text-muted); flex-shrink: 0; }
.picker-empty { font-size: var(--text-sm); color: var(--text-muted); padding: var(--space-4); text-align: center; }
</style>
