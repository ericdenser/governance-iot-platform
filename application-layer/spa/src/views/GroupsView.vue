<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import AppModal from '@/components/AppModal.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import AddDeviceModal from '@/components/groups/AddDeviceModal.vue'
import AssignUserModal from '@/components/groups/AssignUserModal.vue'
import { groupsApi } from '@/services/groups'
import { usersApi } from '@/services/users'
import { useAuthStore } from '@/stores/auth'
import type {
  DeviceGroupResponseDTO,
  GroupMemberResponseDTO,
  GroupDeviceMemberDTO,
  KeycloakUserDTO,
  GroupRole,
} from '@/types/models'
import { errorMessage } from '@/utils/errors'
import { confirm } from '@/composables/useConfirm'
import { toast } from '@/composables/useToast'


const authStore = useAuthStore()

const groups = ref<DeviceGroupResponseDTO[]>([])
const loading = ref(true)

// Selected group detail panel
const selectedGroup = ref<DeviceGroupResponseDTO | null>(null)
const groupDevices = ref<GroupDeviceMemberDTO[]>([])
const groupUsers = ref<GroupMemberResponseDTO[]>([])
const loadingDetail = ref(false)

// Create group modal
const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', description: '' })
const createError = ref('')

const showAddDevice = ref(false)
const alreadyInGroup = computed(() => new Set(groupDevices.value.map(d => d.deviceId)))

// Assign user modal
const showAssignUser = ref(false)
const allUsers = ref<KeycloakUserDTO[]>([])
const loadingAllUsers = ref(false)
const alreadyAssigned = computed(() => new Set(groupUsers.value.map(u => u.keycloakUserId)))

const usernameFor = (id: string) =>
  allUsers.value.find(u => u.keycloakUserId === id)?.username ?? null

const openAssignUser = async () => {
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
const updateRoleTarget = ref<GroupMemberResponseDTO | null>(null)
const newRole = ref<GroupRole | ''>('')
const updateRoleError = ref('')

const openUpdateRole = (u: GroupMemberResponseDTO) => {
  updateRoleTarget.value = u
  newRole.value = u.role
  updateRoleError.value = ''
  showUpdateRole.value = true
}

const doUpdateRole = async () => {
  if (!newRole.value || !selectedGroup.value || !updateRoleTarget.value) return
  updatingRole.value = true; updateRoleError.value = ''
  try {
    await groupsApi.updateRole(selectedGroup.value.groupId, updateRoleTarget.value.keycloakUserId, { role: newRole.value })
    showUpdateRole.value = false
    await selectGroup(selectedGroup.value)
    toast.success('Papel atualizado')
  } catch (e: unknown) {
    updateRoleError.value = errorMessage(e, 'Erro ao atualizar papel.')
  } finally { updatingRole.value = false }
}

// Role-based permissions
const myGroupRole = computed<GroupRole | null>(() => {
  if (!authStore.keycloakUserId || !selectedGroup.value) return null
  return groupUsers.value.find(u => u.keycloakUserId === authStore.keycloakUserId)?.role ?? null
})

const canManageDevices = computed(() =>
  authStore.isAdmin || myGroupRole.value === 'MEMBER' || myGroupRole.value === 'OWNER'
)

const canManageUsers = computed(() =>
  authStore.isAdmin || myGroupRole.value === 'OWNER'
)

const assignableRoles = computed<GroupRole[]>(() =>
  authStore.isAdmin ? ['OWNER', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER']
)

const updateRoleOptions = computed<GroupRole[]>(() =>
  authStore.isAdmin ? ['OWNER', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER']
)

const canUpdateRole = (u: GroupMemberResponseDTO) => {
  if (authStore.isAdmin) return true
  if (myGroupRole.value === 'OWNER' && u.role !== 'OWNER') return true
  return false
}

// Non-members only see that the group exists
const canOpen = (g: DeviceGroupResponseDTO) => authStore.isAdmin || g.myRole != null

const load = async () => {
  const r = await groupsApi.list(true)
  groups.value = Array.isArray(r.data) ? r.data : []
}

const selectGroup = async (g: DeviceGroupResponseDTO) => {
  selectedGroup.value = g
  groupDevices.value = []
  groupUsers.value = []
  if (!canOpen(g)) return
  loadingDetail.value = true
  try {
    const needUsers = !allUsers.value.length
    const [devRes, userRes, usersRes] = await Promise.all([
      groupsApi.listDevices(g.groupId),
      groupsApi.listUsers(g.groupId),
      needUsers ? usersApi.list() : Promise.resolve(null),
    ])
    groupDevices.value = Array.isArray(devRes.data) ? devRes.data : []
    groupUsers.value = Array.isArray(userRes.data) ? userRes.data : []
    if (usersRes && Array.isArray(usersRes.data)) allUsers.value = usersRes.data
  } finally { loadingDetail.value = false }
}

const doCreate = async () => {
  if (!createForm.value.name.trim()) { createError.value = 'Nome é obrigatório.'; return }
  creating.value = true; createError.value = ''
  try {
    await groupsApi.create({ name: createForm.value.name.trim(), description: createForm.value.description.trim() || undefined })
    showCreate.value = false; createForm.value = { name: '', description: '' }
    await load()
    toast.success('Grupo criado')
  } catch (e: unknown) {
    createError.value = errorMessage(e, 'Erro ao criar grupo.')
  } finally { creating.value = false }
}

const doDelete = async (g: DeviceGroupResponseDTO) => {
  const ok = await confirm({
    title: `Excluir grupo "${g.name}"?`,
    message: 'Todos os membros (dispositivos e usuários) serão removidos.',
    confirmText: 'Excluir',
    danger: true,
  })
  if (!ok) return
  try {
    await groupsApi.delete(g.groupId)
    if (selectedGroup.value?.groupId === g.groupId) selectedGroup.value = null
    await load()
    toast.success(`Grupo "${g.name}" excluído`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao excluir grupo.'))
  }
}

const onDeviceAdded = async () => {
  showAddDevice.value = false
  if (selectedGroup.value) await selectGroup(selectedGroup.value)
  toast.success('Dispositivo adicionado ao grupo')
}

const doRemoveDevice = async (deviceId: string) => {
  if (!selectedGroup.value) return
  const ok = await confirm({
    title: 'Remover dispositivo?',
    message: 'O dispositivo sai do grupo mas continua no CMDB.',
    confirmText: 'Remover',
    danger: true,
  })
  if (!ok) return
  try {
    await groupsApi.removeDevice(selectedGroup.value.groupId, deviceId)
    await selectGroup(selectedGroup.value)
    toast.success('Dispositivo removido do grupo')
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao remover dispositivo.'))
  }
}

const onUserAssigned = async () => {
  showAssignUser.value = false
  if (selectedGroup.value) await selectGroup(selectedGroup.value)
  toast.success('Usuário atribuído ao grupo')
}

const doRemoveUser = async (userId: string) => {
  if (!selectedGroup.value) return
  const ok = await confirm({
    title: 'Remover usuário do grupo?',
    message: 'O usuário perde acesso a esse grupo. Ele continua no Keycloak.',
    confirmText: 'Remover',
    danger: true,
  })
  if (!ok) return
  try {
    await groupsApi.removeUser(selectedGroup.value.groupId, userId)
    await selectGroup(selectedGroup.value)
    toast.success('Usuário removido do grupo')
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao remover usuário.'))
  }
}

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <div class="groups-grid">
      <!-- Group list -->
      <AppCard>
        <template #actions>
          <AppButton v-if="authStore.isAdmin" size="md" variant="primary" @click="showCreate = true">Novo Grupo</AppButton>
        </template>

        <AppSkeleton v-if="loading" variant="list" :rows="4" />
        <ul v-else class="group-list">
          <li
            v-for="g in groups" :key="g.groupId"
            class="group-item"
            :class="{ active: selectedGroup?.groupId === g.groupId }"
            @click="selectGroup(g)"
          >
            <div class="group-info">
              <span class="group-name">
                {{ g.name }}
                <svg v-if="!canOpen(g)" class="lock" width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-label="Sem acesso"><rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              </span>
              <span class="group-desc text-muted text-xs" v-if="g.description">{{ g.description }}</span>
            </div>
            <AppButton
              v-if="authStore.isAdmin"
              size="sm" variant="danger"
              @click.stop="doDelete(g)"
            >Excluir</AppButton>
          </li>
          <li v-if="!groups.length">
            <EmptyState compact title="Nenhum grupo cadastrado"
              :hint="authStore.isAdmin ? 'Grupos definem quem vê e gerencia cada dispositivo e firmware.' : 'Peça a um administrador para criar um grupo e incluir você.'">
              <AppButton v-if="authStore.isAdmin" variant="primary" @click="showCreate = true">Novo Grupo</AppButton>
            </EmptyState>
          </li>
        </ul>
      </AppCard>

      <!-- Group detail -->
      <div v-if="selectedGroup" class="detail-col">
        <AppCard :title="selectedGroup.name">
          <template v-if="canOpen(selectedGroup)" #actions>
            <div style="display: flex; gap: var(--space-2);">
              <AppButton v-if="canManageDevices" size="md" variant="secondary" @click="showAddDevice = true">Adicionar Dispositivo</AppButton>
              <AppButton v-if="canManageUsers" size="md" variant="secondary" @click="openAssignUser">Adicionar Usuário</AppButton>
            </div>
          </template>

          <div v-if="!canOpen(selectedGroup)" class="placeholder">
            <div class="placeholder-inner no-access">
              <p v-if="selectedGroup.description" class="text-sm">{{ selectedGroup.description }}</p>
              <p>Você não faz parte deste grupo. Peça a um OWNER do grupo ou a um administrador para ser incluído.</p>
            </div>
          </div>
          <AppSkeleton v-else-if="loadingDetail" variant="table" :rows="4" />
          <div v-else class="detail-sections">
            <!-- Devices -->
            <div class="section">
              <h4 class="section-title">Dispositivos ({{ groupDevices.length }})</h4>
              <EmptyState v-if="!groupDevices.length" compact title="Nenhum dispositivo neste grupo">
                <AppButton v-if="canManageDevices" size="sm" variant="secondary" @click="showAddDevice = true">Adicionar Dispositivo</AppButton>
              </EmptyState>
              <table v-else class="tbl aligned">
                <colgroup>
                  <col class="col-name" />
                  <col class="col-role" />
                  <col class="col-assigned" />
                  <col class="col-actions" />
                </colgroup>
                <thead>
                  <tr>
                    <th>Dispositivo</th>
                    <th></th>
                    <th>Atribuído por</th>
                    <th></th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="d in groupDevices" :key="d.deviceId">
                    <td>
                      <div class="cell-main">{{ d.name }}</div>
                      <div class="mono text-xs text-muted">{{ d.deviceId }}</div>
                    </td>
                    <td></td>
                    <td class="text-xs text-muted">{{ d.addedByUsername ?? '—' }}</td>
                    <td class="col-actions-cell">
                      <AppButton v-if="canManageDevices" size="sm" variant="ghost" @click="doRemoveDevice(d.deviceId)">Remover</AppButton>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>

            <!-- Users -->
            <div class="section">
              <h4 class="section-title">Usuários ({{ groupUsers.length }})</h4>
              <EmptyState v-if="!groupUsers.length" compact title="Nenhum usuário neste grupo">
                <AppButton v-if="canManageUsers" size="sm" variant="secondary" @click="openAssignUser">Adicionar Usuário</AppButton>
              </EmptyState>
              <table v-else class="tbl aligned">
                <colgroup>
                  <col class="col-name" />
                  <col class="col-role" />
                  <col class="col-assigned" />
                  <col class="col-actions" />
                </colgroup>
                <thead>
                  <tr>
                    <th>Usuário</th>
                    <th>Papel</th>
                    <th>Atribuído por</th>
                    <th></th>
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
                    <td class="col-actions-cell">
                      <div class="row-actions">
                        <AppButton v-if="canUpdateRole(u)" size="sm" variant="secondary" @click="openUpdateRole(u)">Atualizar Papel</AppButton>
                        <AppButton v-if="canManageUsers" size="sm" variant="ghost" @click="doRemoveUser(u.keycloakUserId)">Remover</AppButton>
                      </div>
                    </td>
                  </tr>
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
        <label for="groups-f1">Nome</label>
        <input id="groups-f1" v-model="createForm.name" class="field" placeholder="ex: Armazém Norte" @keydown.enter="doCreate" />
      </div>
      <div class="form-group">
        <label for="groups-f2">Descrição <span class="text-muted">(opcional)</span></label>
        <input id="groups-f2" v-model="createForm.description" class="field" placeholder="Descrição do grupo..." />
      </div>
      <p v-if="createError" class="error">{{ createError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showCreate = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="creating" @click="doCreate">Criar</AppButton>
      </template>
    </AppModal>

    <AddDeviceModal v-if="showAddDevice && selectedGroup"
      :group-id="selectedGroup.groupId" :exclude-ids="alreadyInGroup"
      @close="showAddDevice = false" @added="onDeviceAdded" />

    <AssignUserModal v-if="showAssignUser && selectedGroup"
      :group-id="selectedGroup.groupId" :users="allUsers" :loading="loadingAllUsers"
      :exclude-ids="alreadyAssigned" :roles="assignableRoles"
      @close="showAssignUser = false" @assigned="onUserAssigned" />

    <AppModal title="Atualizar Papel" :show="showUpdateRole" @close="showUpdateRole = false">
      <p class="text-sm text-muted" v-if="updateRoleTarget">
        Usuário: <strong>{{ usernameFor(updateRoleTarget.keycloakUserId) ?? updateRoleTarget.keycloakUserId }}</strong>
      </p>
      <div class="form-group">
        <label for="groups-f4">Novo Papel</label>
        <select id="groups-f4" v-model="newRole" class="field">
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
.group-item.active { background: var(--border); }
.group-item.active .group-name { font-weight: 600; }
.group-info { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.group-name { font-size: var(--text-sm); font-weight: 500; color: var(--text); display: inline-flex; align-items: center; gap: 6px; }
.lock { color: var(--text-muted); flex-shrink: 0; }
.group-desc { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.detail-col { min-width: 0; }
.placeholder { display: flex; align-items: center; justify-content: center; min-height: 160px; }
.placeholder-inner { color: var(--text-muted); font-size: var(--text-sm); }
.no-access { max-width: 420px; text-align: center; display: flex; flex-direction: column; gap: var(--space-2); }
.no-access p { margin: 0; }

.detail-sections { display: flex; flex-direction: column; gap: var(--space-10); }
.section {}
.section-title { font-size: var(--text-sm); font-weight: 600; color: var(--text-secondary); margin: 0 0 var(--space-4); }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); font-weight: 500; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-2) 12px var(--space-2) 0; border-top: 1px solid var(--border); }

/* Alinhamento consistente entre tabelas de Dispositivos e Usuários — colunas
   compartilhadas mesmo quando uma delas não tem valor (Papel só em usuários). */
.tbl.aligned { table-layout: fixed; }
.tbl.aligned .col-name     { width: 40%; }
.tbl.aligned .col-role     { width: 15%; }
.tbl.aligned .col-assigned { width: 25%; }
.tbl.aligned .col-actions  { width: 20%; }
.tbl.aligned .col-actions-cell { text-align: right; }
.tbl.aligned td { overflow-wrap: anywhere; }
/* Buttons stack instead of spilling over Papel / Atribuido por on narrow widths */
.row-actions { display: flex; flex-wrap: wrap; gap: var(--space-1); justify-content: flex-end; }
.cell-main { font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.font-medium { font-weight: 500; }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-6) 0; font-size: var(--text-sm); }

.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.error { color: var(--danger); font-size: var(--text-sm); margin: 0; }

</style>
