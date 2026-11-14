<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppButton from '@/components/AppButton.vue'
import AppModal from '@/components/AppModal.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import StatusLabel from '@/components/StatusLabel.vue'
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

const ROLE_LABEL: Record<GroupRole, string> = { OWNER: 'Owner', MEMBER: 'Member', VIEWER: 'Viewer' }

const groups = ref<DeviceGroupResponseDTO[]>([])
const loading = ref(true)

const selected = ref<DeviceGroupResponseDTO | null>(null)
const tab = ref<'devices' | 'users'>('devices')
const groupDevices = ref<GroupDeviceMemberDTO[]>([])
const groupUsers = ref<GroupMemberResponseDTO[]>([])
const loadingDetail = ref(false)

const allUsers = ref<KeycloakUserDTO[]>([])
const loadingAllUsers = ref(false)
const userOf = (id: string) => allUsers.value.find(u => u.keycloakUserId === id) ?? null

const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({ name: '', description: '' })
const createError = ref('')

const showAddDevice = ref(false)
const showAssignUser = ref(false)
const savingRoleFor = ref<string | null>(null)

const alreadyInGroup = computed(() => new Set(groupDevices.value.map(d => d.deviceId)))
const alreadyAssigned = computed(() => new Set(groupUsers.value.map(u => u.keycloakUserId)))

// ── Permissions ────────────────────────────────────────────────────────────
const myRole = computed<GroupRole | null>(() => {
  if (!authStore.keycloakUserId || !selected.value) return null
  return groupUsers.value.find(u => u.keycloakUserId === authStore.keycloakUserId)?.role ?? selected.value.myRole
})
const canManageDevices = computed(() => authStore.isAdmin || myRole.value === 'MEMBER' || myRole.value === 'OWNER')
const canManageUsers = computed(() => authStore.isAdmin || myRole.value === 'OWNER')
const assignableRoles = computed<GroupRole[]>(() =>
  authStore.isAdmin ? ['OWNER', 'MEMBER', 'VIEWER'] : ['MEMBER', 'VIEWER'])
const canChangeRole = (u: GroupMemberResponseDTO) =>
  authStore.isAdmin || (myRole.value === 'OWNER' && u.role !== 'OWNER')

// Non-members only see that the group exists
const canOpen = (g: DeviceGroupResponseDTO) => authStore.isAdmin || g.myRole != null

// ── Loaders ────────────────────────────────────────────────────────────────
const load = async () => {
  const r = await groupsApi.list(true)
  groups.value = Array.isArray(r.data) ? r.data : []
}

const loadDetail = async (g: DeviceGroupResponseDTO) => {
  const needUsers = !allUsers.value.length
  const [devRes, userRes, usersRes] = await Promise.all([
    groupsApi.listDevices(g.groupId),
    groupsApi.listUsers(g.groupId),
    needUsers ? usersApi.list().catch(() => null) : Promise.resolve(null),
  ])
  if (selected.value?.groupId !== g.groupId) return
  groupDevices.value = Array.isArray(devRes.data) ? devRes.data : []
  groupUsers.value = Array.isArray(userRes.data) ? userRes.data : []
  if (usersRes && Array.isArray(usersRes.data)) allUsers.value = usersRes.data
}

const selectGroup = async (g: DeviceGroupResponseDTO) => {
  selected.value = g
  tab.value = 'devices'
  groupDevices.value = []
  groupUsers.value = []
  if (!canOpen(g)) return
  loadingDetail.value = true
  try { await loadDetail(g) } finally { loadingDetail.value = false }
}

// After an action: same group, no skeleton flash
const refresh = async () => { if (selected.value) await loadDetail(selected.value) }

// ── Actions ────────────────────────────────────────────────────────────────
const doCreate = async () => {
  if (!createForm.value.name.trim()) { createError.value = 'Nome é obrigatório.'; return }
  creating.value = true; createError.value = ''
  try {
    const r = await groupsApi.create({ name: createForm.value.name.trim(), description: createForm.value.description.trim() || undefined })
    showCreate.value = false; createForm.value = { name: '', description: '' }
    await load()
    toast.success('Grupo criado')
    const created = groups.value.find(g => g.groupId === r.data?.groupId)
    if (created) selectGroup(created)
  } catch (e: unknown) {
    createError.value = errorMessage(e, 'Erro ao criar grupo.')
  } finally { creating.value = false }
}

const doDelete = async () => {
  const g = selected.value
  if (!g) return
  const ok = await confirm({
    title: `Excluir grupo "${g.name}"?`,
    message: 'Dispositivos e usuários saem do grupo. Os dispositivos continuam cadastrados.',
    confirmText: 'Excluir grupo',
    danger: true,
  })
  if (!ok) return
  try {
    await groupsApi.delete(g.groupId)
    selected.value = null
    await load()
    toast.success(`Grupo "${g.name}" excluído`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao excluir grupo.'))
  }
}

const onDeviceAdded = async () => {
  showAddDevice.value = false
  await refresh()
  toast.success('Dispositivo adicionado ao grupo')
}

const removeDevice = async (d: GroupDeviceMemberDTO) => {
  if (!selected.value) return
  const ok = await confirm({
    title: `Remover "${d.name}" do grupo?`,
    message: 'O dispositivo sai do grupo e continua cadastrado.',
    confirmText: 'Remover',
    danger: true,
  })
  if (!ok) return
  try {
    await groupsApi.removeDevice(selected.value.groupId, d.deviceId)
    await refresh()
    toast.success('Dispositivo removido do grupo')
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao remover dispositivo.'))
  }
}

const openAssignUser = async () => {
  showAssignUser.value = true
  if (!allUsers.value.length) {
    loadingAllUsers.value = true
    try { allUsers.value = (await usersApi.list()).data ?? [] } finally { loadingAllUsers.value = false }
  }
}

const onUserAssigned = async () => {
  showAssignUser.value = false
  await refresh()
  toast.success('Usuário adicionado ao grupo')
}

const changeRole = async (u: GroupMemberResponseDTO, e: Event) => {
  const role = (e.target as HTMLSelectElement).value as GroupRole
  if (!selected.value || role === u.role) return
  savingRoleFor.value = u.keycloakUserId
  try {
    await groupsApi.updateRole(selected.value.groupId, u.keycloakUserId, { role })
    toast.success(`Papel alterado para ${ROLE_LABEL[role]}`)
  } catch (err: unknown) {
    toast.error(errorMessage(err, 'Erro ao alterar papel.'))
  } finally {
    // reload either way so the select shows what the server has
    await refresh()
    savingRoleFor.value = null
  }
}

const removeUser = async (u: GroupMemberResponseDTO) => {
  if (!selected.value) return
  const name = userOf(u.keycloakUserId)?.username ?? 'este usuário'
  const ok = await confirm({
    title: `Remover ${name} do grupo?`,
    message: 'Ele perde o acesso a este grupo. A conta continua existindo.',
    confirmText: 'Remover',
    danger: true,
  })
  if (!ok) return
  try {
    await groupsApi.removeUser(selected.value.groupId, u.keycloakUserId)
    await refresh()
    toast.success('Usuário removido do grupo')
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao remover usuário.'))
  }
}

onMounted(async () => {
  try {
    await load()
    // Side by side there is room for a detail; on narrow screens the list comes first
    const first = groups.value.find(canOpen)
    if (first && window.matchMedia('(min-width: 1024px)').matches) selectGroup(first)
  } finally { loading.value = false }
})
</script>

<template>
  <AppLayout>
    <div class="groups" :class="{ 'has-selection': selected }">

      <!-- ── Group list ─────────────────────────────────────────────── -->
      <section class="pane list-pane" aria-label="Grupos">
        <header class="pane-head">
          <span class="pane-count">{{ groups.length }} {{ groups.length === 1 ? 'grupo' : 'grupos' }}</span>
          <AppButton v-if="authStore.isAdmin" size="sm" variant="primary" @click="showCreate = true">Novo grupo</AppButton>
        </header>

        <AppSkeleton v-if="loading" variant="list" :rows="4" />
        <EmptyState v-else-if="!groups.length" compact title="Nenhum grupo cadastrado"
          :hint="authStore.isAdmin ? 'Grupos definem quem vê e gerencia cada dispositivo e firmware.' : 'Peça a um administrador para criar um grupo e incluir você.'" />
        <ul v-else class="group-list">
          <li v-for="g in groups" :key="g.groupId">
            <button type="button" class="group-row" :class="{ active: selected?.groupId === g.groupId }"
                    :aria-current="selected?.groupId === g.groupId ? 'true' : undefined" @click="selectGroup(g)">
              <span class="group-name">{{ g.name }}</span>
              <span class="group-role" :class="{ locked: !canOpen(g) }">
                {{ g.myRole ? ROLE_LABEL[g.myRole] : (authStore.isAdmin ? 'Admin' : 'Sem acesso') }}
              </span>
              <span v-if="g.description" class="group-desc">{{ g.description }}</span>
            </button>
          </li>
        </ul>
      </section>

      <!-- ── Group detail ───────────────────────────────────────────── -->
      <section class="pane detail-pane" aria-label="Detalhes do grupo">
        <EmptyState v-if="!selected" title="Selecione um grupo"
          hint="Os dispositivos e usuários do grupo aparecem aqui." />

        <template v-else>
          <button type="button" class="back-btn" @click="selected = null">← Grupos</button>

          <header class="detail-head">
            <div class="detail-title">
              <h1 class="detail-name">{{ selected.name }}</h1>
              <p v-if="selected.description" class="detail-desc">{{ selected.description }}</p>
            </div>
            <AppButton v-if="authStore.isAdmin" size="sm" variant="danger-ghost" @click="doDelete">Excluir grupo</AppButton>
          </header>

          <EmptyState v-if="!canOpen(selected)" title="Você não faz parte deste grupo"
            hint="Peça a um Owner do grupo ou a um administrador para ser incluído." />

          <template v-else>
            <div class="tabbar">
              <div class="tabs" role="tablist" aria-label="Conteúdo do grupo">
                <button type="button" role="tab" class="tab" :class="{ active: tab === 'devices' }"
                        :aria-selected="tab === 'devices'" @click="tab = 'devices'">
                  Dispositivos <span class="tab-count">{{ groupDevices.length }}</span>
                </button>
                <button type="button" role="tab" class="tab" :class="{ active: tab === 'users' }"
                        :aria-selected="tab === 'users'" @click="tab = 'users'">
                  Usuários <span class="tab-count">{{ groupUsers.length }}</span>
                </button>
              </div>
              <AppButton v-if="tab === 'devices' && canManageDevices" size="sm" variant="secondary" @click="showAddDevice = true">Adicionar dispositivo</AppButton>
              <AppButton v-if="tab === 'users' && canManageUsers" size="sm" variant="secondary" @click="openAssignUser">Adicionar usuário</AppButton>
            </div>

            <AppSkeleton v-if="loadingDetail" variant="list" :rows="4" />

            <!-- Devices -->
            <template v-else-if="tab === 'devices'">
              <EmptyState v-if="!groupDevices.length" compact title="Nenhum dispositivo neste grupo"
                :hint="canManageDevices ? 'Adicione um dispositivo para que os usuários do grupo possam vê-lo.' : undefined" />
              <ul v-else class="rows">
                <li v-for="d in groupDevices" :key="d.deviceId" class="row">
                  <div class="row-main">
                    <router-link :to="`/devices/${d.deviceId}`" class="row-name">{{ d.name }}</router-link>
                    <span class="row-sub mono" :title="d.deviceId">{{ d.deviceId.slice(0, 8) }}</span>
                  </div>
                  <StatusLabel kind="device" :code="d.status" class="row-status" />
                  <span class="row-by">{{ d.addedByUsername ? `por ${d.addedByUsername}` : '' }}</span>
                  <AppButton v-if="canManageDevices" size="sm" variant="ghost" class="row-action"
                             :aria-label="`Remover ${d.name} do grupo`" @click="removeDevice(d)">Remover</AppButton>
                </li>
              </ul>
            </template>

            <!-- Users -->
            <template v-else>
              <p class="roles-hint">
                Owner gerencia usuários, Member gerencia dispositivos e firmware, Viewer só consulta.
              </p>
              <EmptyState v-if="!groupUsers.length" compact title="Nenhum usuário neste grupo"
                :hint="canManageUsers ? 'Adicione quem precisa ver ou gerenciar estes dispositivos.' : undefined" />
              <ul v-else class="rows">
                <li v-for="u in groupUsers" :key="u.keycloakUserId" class="row">
                  <div class="row-main">
                    <span class="row-name">{{ userOf(u.keycloakUserId)?.username ?? u.keycloakUserId.slice(0, 8) }}</span>
                    <span v-if="userOf(u.keycloakUserId)?.email" class="row-sub">{{ userOf(u.keycloakUserId)?.email }}</span>
                  </div>
                  <select v-if="canChangeRole(u)" class="field role-select" :value="u.role"
                          :disabled="savingRoleFor === u.keycloakUserId"
                          :aria-label="`Papel de ${userOf(u.keycloakUserId)?.username ?? 'usuário'}`"
                          @change="changeRole(u, $event)">
                    <option v-for="r in assignableRoles" :key="r" :value="r">{{ ROLE_LABEL[r] }}</option>
                    <option v-if="!assignableRoles.includes(u.role)" :value="u.role">{{ ROLE_LABEL[u.role] }}</option>
                  </select>
                  <span v-else class="row-role">{{ ROLE_LABEL[u.role] }}</span>
                  <span class="row-by">{{ u.assignedByUsername ? `por ${u.assignedByUsername}` : '' }}</span>
                  <AppButton v-if="canManageUsers" size="sm" variant="ghost" class="row-action"
                             :aria-label="`Remover ${userOf(u.keycloakUserId)?.username ?? 'usuário'} do grupo`"
                             @click="removeUser(u)">Remover</AppButton>
                </li>
              </ul>
            </template>
          </template>
        </template>
      </section>
    </div>

    <AppModal title="Novo grupo" :show="showCreate" @close="showCreate = false">
      <div class="form-group">
        <label for="groups-f1">Nome</label>
        <input id="groups-f1" v-model="createForm.name" class="field" placeholder="ex: Armazém Norte" @keydown.enter="doCreate" />
      </div>
      <div class="form-group">
        <label for="groups-f2">Descrição <span class="text-muted">(opcional)</span></label>
        <input id="groups-f2" v-model="createForm.description" class="field" placeholder="Para que serve o grupo" @keydown.enter="doCreate" />
      </div>
      <p v-if="createError" class="field-error">{{ createError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showCreate = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="creating" @click="doCreate">Criar grupo</AppButton>
      </template>
    </AppModal>

    <AddDeviceModal v-if="showAddDevice && selected"
      :group-id="selected.groupId" :exclude-ids="alreadyInGroup"
      @close="showAddDevice = false" @added="onDeviceAdded" />

    <AssignUserModal v-if="showAssignUser && selected"
      :group-id="selected.groupId" :users="allUsers" :loading="loadingAllUsers"
      :exclude-ids="alreadyAssigned" :roles="assignableRoles"
      @close="showAssignUser = false" @assigned="onUserAssigned" />
  </AppLayout>
</template>

<style scoped>
.groups { display: grid; grid-template-columns: minmax(220px, 280px) minmax(0, 1fr); gap: var(--space-4); align-items: start; }

.pane { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); min-width: 0; }
.list-pane { padding: var(--space-3); }
.detail-pane { padding: var(--space-5); display: flex; flex-direction: column; gap: var(--space-4); }

/* ── List ─────────────────────────────────────────────────────────────── */
.pane-head { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); padding: var(--space-1) var(--space-2) var(--space-3); }
.pane-count { font-size: var(--text-xs); color: var(--text-muted); }

.group-list { list-style: none; display: flex; flex-direction: column; gap: 2px; }
.group-row {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; column-gap: var(--space-2); row-gap: 2px;
  width: 100%; padding: var(--space-2) var(--space-3);
  background: none; border: none; border-left: 2px solid transparent; border-radius: var(--radius-sm);
  font-family: var(--font-sans); text-align: left; cursor: pointer;
  transition: background var(--transition);
}
.group-row:hover { background: var(--panel); }
.group-row.active { background: var(--panel); border-left-color: var(--accent); }
.group-name { font-size: var(--text-sm); font-weight: 500; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.group-role { font-size: var(--text-xs); color: var(--text-secondary); white-space: nowrap; }
.group-role.locked { color: var(--text-muted); }
.group-desc { grid-column: 1 / -1; font-size: var(--text-xs); color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* ── Detail ───────────────────────────────────────────────────────────── */
.back-btn { display: none; align-self: flex-start; background: none; border: none; padding: 0; color: var(--text-muted); font-size: var(--text-sm); cursor: pointer; }
.back-btn:hover { color: var(--text); }

.detail-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--space-3); flex-wrap: wrap; }
.detail-title { min-width: 0; }
.detail-name { font-size: var(--text-xl); font-weight: 600; margin: 0; overflow-wrap: anywhere; }
.detail-desc { margin: var(--space-1) 0 0; font-size: var(--text-sm); color: var(--text-muted); max-width: 70ch; }

.tabbar { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2) var(--space-3); flex-wrap: wrap; border-bottom: 1px solid var(--border); }
.tabs { display: flex; gap: var(--space-1); }
.tab { background: none; border: none; border-bottom: 2px solid transparent; margin-bottom: -1px; padding: var(--space-2) var(--space-3); color: var(--text-muted); font-family: var(--font-sans); font-size: var(--text-sm); font-weight: 500; cursor: pointer; white-space: nowrap; transition: color var(--transition); }
.tab:hover { color: var(--text); }
.tab.active { color: var(--text); border-bottom-color: var(--accent); }
.tab-count { margin-left: var(--space-1); font-size: var(--text-xs); font-weight: 400; color: var(--text-muted); font-variant-numeric: tabular-nums; }
.tabbar > :deep(button.sm) { margin-bottom: var(--space-2); }

.roles-hint { margin: 0; font-size: var(--text-xs); color: var(--text-muted); }

/* One member per line; wraps instead of squeezing when the pane is narrow */
.rows { list-style: none; display: flex; flex-direction: column; }
.row { display: flex; align-items: center; flex-wrap: wrap; gap: var(--space-2) var(--space-4); padding: var(--space-3) 0; border-top: 1px solid var(--border); }
.row:first-child { border-top: none; padding-top: 0; }
.row-main { flex: 1 1 200px; min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.row-name { font-size: var(--text-sm); font-weight: 500; color: var(--text); overflow-wrap: anywhere; }
a.row-name:hover { color: var(--accent); }
.row-sub { font-size: var(--text-xs); color: var(--text-muted); overflow-wrap: anywhere; }
.row-status { font-size: var(--text-xs); }
.row-role { font-size: var(--text-sm); color: var(--text-secondary); min-width: 96px; }
.role-select { width: auto; min-width: 96px; padding: 5px 8px; }
.row-by { flex: 0 1 140px; font-size: var(--text-xs); color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.row-action { margin-left: auto; }

.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }

/* ── Narrow: one pane at a time ───────────────────────────────────────── */
@media (max-width: 1023px) {
  .groups { grid-template-columns: minmax(0, 1fr); }
  .groups:not(.has-selection) .detail-pane { display: none; }
  .groups.has-selection .list-pane { display: none; }
  .back-btn { display: block; }
  .detail-pane { padding: var(--space-4); }
}
@media (max-width: 540px) {
  .row-by { display: none; }
  /* Add button drops below the tabs: the rule stays under the tabs */
  .tabbar { border-bottom: none; }
  .tabs { width: 100%; border-bottom: 1px solid var(--border); }
}
</style>
