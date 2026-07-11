<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppButton from '@/components/AppButton.vue'
import AppBadge from '@/components/AppBadge.vue'
import AppModal from '@/components/AppModal.vue'
import { usersApi } from '@/services/users'
import type { KeycloakUserDTO } from '@/types/models'
import { errorMessage } from '@/utils/errors'
import { confirm } from '@/composables/useConfirm'
import { toast } from '@/composables/useToast'

const users = ref<KeycloakUserDTO[]>([])
const loading = ref(true)
const search = ref('')

const showCreate = ref(false)
const creating = ref(false)
const createForm = ref({
  username: '',
  email: '',
  firstName: '',
  lastName: '',
  temporaryPassword: '',
})
const createError = ref('')

const showEdit = ref(false)
const editing = ref(false)
const editTarget = ref<KeycloakUserDTO | null>(null)
const editForm = ref({ email: '', firstName: '', lastName: '' })
const editError = ref('')

const showReset = ref(false)
const resetting = ref(false)
const resetTarget = ref<KeycloakUserDTO | null>(null)
const resetForm = ref({ newPassword: '', temporary: true })
const resetError = ref('')

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  if (!q) return users.value
  return users.value.filter(u =>
    u.username?.toLowerCase().includes(q) ||
    u.email?.toLowerCase().includes(q) ||
    u.firstName?.toLowerCase().includes(q) ||
    u.lastName?.toLowerCase().includes(q)
  )
})

const fullName = (u: KeycloakUserDTO) => {
  const parts = [u.firstName, u.lastName].filter(Boolean)
  return parts.length ? parts.join(' ') : '—'
}

const load = async () => {
  loading.value = true
  try {
    const r = await usersApi.list()
    users.value = Array.isArray(r.data) ? r.data : []
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao carregar usuários.'))
  } finally { loading.value = false }
}

const openCreate = () => {
  createForm.value = { username: '', email: '', firstName: '', lastName: '', temporaryPassword: '' }
  createError.value = ''
  showCreate.value = true
}

const doCreate = async () => {
  const form = createForm.value
  if (!form.username.trim()) { createError.value = 'Username é obrigatório.'; return }
  creating.value = true; createError.value = ''
  try {
    await usersApi.create({
      username: form.username.trim(),
      email: form.email.trim() || null,
      firstName: form.firstName.trim() || null,
      lastName: form.lastName.trim() || null,
      enabled: true,
      emailVerified: false,
      temporaryPassword: form.temporaryPassword.trim() || null,
    })
    showCreate.value = false
    await load()
    toast.success(`Usuário "${form.username}" criado`)
  } catch (e: unknown) {
    createError.value = errorMessage(e, 'Erro ao criar usuário.')
  } finally { creating.value = false }
}

const openEdit = (u: KeycloakUserDTO) => {
  editTarget.value = u
  editForm.value = {
    email: u.email ?? '',
    firstName: u.firstName ?? '',
    lastName: u.lastName ?? '',
  }
  editError.value = ''
  showEdit.value = true
}

const doEdit = async () => {
  if (!editTarget.value) return
  editing.value = true; editError.value = ''
  try {
    await usersApi.update(editTarget.value.keycloakUserId, {
      email: editForm.value.email.trim() || null,
      firstName: editForm.value.firstName.trim() || null,
      lastName: editForm.value.lastName.trim() || null,
    })
    showEdit.value = false
    await load()
    toast.success('Usuário atualizado')
  } catch (e: unknown) {
    editError.value = errorMessage(e, 'Erro ao atualizar usuário.')
  } finally { editing.value = false }
}

const openReset = (u: KeycloakUserDTO) => {
  resetTarget.value = u
  resetForm.value = { newPassword: '', temporary: true }
  resetError.value = ''
  showReset.value = true
}

const doReset = async () => {
  if (!resetTarget.value) return
  if (resetForm.value.newPassword.length < 8) {
    resetError.value = 'Senha precisa de ao menos 8 caracteres.'
    return
  }
  resetting.value = true; resetError.value = ''
  try {
    await usersApi.resetPassword(resetTarget.value.keycloakUserId, {
      newPassword: resetForm.value.newPassword,
      temporary: resetForm.value.temporary,
    })
    showReset.value = false
    toast.success(`Senha ${resetForm.value.temporary ? 'temporária ' : ''}definida`)
  } catch (e: unknown) {
    resetError.value = errorMessage(e, 'Erro ao redefinir senha.')
  } finally { resetting.value = false }
}

const toggleEnabled = async (u: KeycloakUserDTO) => {
  const next = !u.enabled
  const ok = await confirm({
    title: next ? 'Habilitar usuário?' : 'Desabilitar usuário?',
    message: next
      ? `"${u.username}" poderá fazer login normalmente.`
      : `"${u.username}" não poderá fazer login enquanto estiver desabilitado. Sessões ativas persistem até expirar.`,
    confirmText: next ? 'Habilitar' : 'Desabilitar',
    danger: !next,
  })
  if (!ok) return
  try {
    await usersApi.update(u.keycloakUserId, { enabled: next })
    await load()
    toast.success(next ? 'Usuário habilitado' : 'Usuário desabilitado')
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao alterar status.'))
  }
}

const doDelete = async (u: KeycloakUserDTO) => {
  const ok = await confirm({
    title: `Excluir "${u.username}"?`,
    message: 'O usuário será removido do Keycloak. Ele perde acesso a TODOS os sistemas que usam esse realm. Ação irreversível.',
    confirmText: 'Excluir',
    danger: true,
  })
  if (!ok) return
  try {
    await usersApi.delete(u.keycloakUserId)
    await load()
    toast.success(`Usuário "${u.username}" excluído`)
  } catch (e: unknown) {
    toast.error(errorMessage(e, 'Erro ao excluir usuário.'))
  }
}

onMounted(load)
</script>

<template>
  <AppLayout>
    <AppCard title="Usuários">
      <template #actions>
        <div class="header-actions">
          <input
            v-model="search"
            class="field search-field"
            placeholder="Buscar por username, nome ou e-mail..."
          />
          <AppButton variant="primary" @click="openCreate">Novo Usuário</AppButton>
        </div>
      </template>

      <div v-if="loading" class="empty">Carregando...</div>
      <div v-else-if="!filtered.length" class="empty">
        {{ users.length ? 'Nenhum usuário encontrado com esse filtro.' : 'Nenhum usuário cadastrado.' }}
      </div>
      <table v-else class="tbl">
        <thead>
          <tr>
            <th>Username</th>
            <th>Nome</th>
            <th>E-mail</th>
            <th style="width: 90px;">Status</th>
            <th style="width: 280px; text-align: right;"></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="u in filtered" :key="u.keycloakUserId">
            <td>
              <div class="cell-main">{{ u.username }}</div>
              <div class="mono text-xs text-muted">{{ u.keycloakUserId.slice(0, 8) }}…</div>
            </td>
            <td class="text-sm">{{ fullName(u) }}</td>
            <td class="text-sm">
              <span v-if="u.email">{{ u.email }}</span>
              <span v-else class="text-muted">—</span>
            </td>
            <td>
              <AppBadge :variant="u.enabled ? 'success' : 'muted'">
                {{ u.enabled ? 'Ativo' : 'Desabilitado' }}
              </AppBadge>
            </td>
            <td style="text-align: right;">
              <div class="row-actions">
                <AppButton size="sm" variant="secondary" @click="openEdit(u)">Editar</AppButton>
                <AppButton size="sm" variant="secondary" @click="openReset(u)">Senha</AppButton>
                <AppButton size="sm" variant="ghost" @click="toggleEnabled(u)">
                  {{ u.enabled ? 'Desabilitar' : 'Habilitar' }}
                </AppButton>
                <AppButton size="sm" variant="danger" @click="doDelete(u)">Excluir</AppButton>
              </div>
            </td>
          </tr>
        </tbody>
      </table>
    </AppCard>

    <!-- Criar -->
    <AppModal title="Novo Usuário" :show="showCreate" @close="showCreate = false">
      <div class="form-group">
        <label>Username <span class="req">*</span></label>
        <input v-model="createForm.username" class="field" placeholder="ex: joao.silva" />
      </div>
      <div class="form-row">
        <div class="form-group">
          <label>Nome</label>
          <input v-model="createForm.firstName" class="field" placeholder="João" />
        </div>
        <div class="form-group">
          <label>Sobrenome</label>
          <input v-model="createForm.lastName" class="field" placeholder="Silva" />
        </div>
      </div>
      <div class="form-group">
        <label>E-mail</label>
        <input v-model="createForm.email" class="field" type="email" placeholder="joao@exemplo.com" />
      </div>
      <div class="form-group">
        <label>Senha temporária</label>
        <input v-model="createForm.temporaryPassword" class="field" type="text" placeholder="Mínimo 8 caracteres" />
        <p class="hint">
          O usuário será obrigado a trocar no primeiro login. Se deixar vazio, será necessário definir senha manualmente depois.
        </p>
      </div>
      <p v-if="createError" class="error">{{ createError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showCreate = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="creating" @click="doCreate">Criar</AppButton>
      </template>
    </AppModal>

    <!-- Editar -->
    <AppModal title="Editar Usuário" :show="showEdit" @close="showEdit = false">
      <p class="text-sm text-muted" v-if="editTarget">
        Username: <strong>{{ editTarget.username }}</strong>
        <span class="text-muted"> (não editável)</span>
      </p>
      <div class="form-row">
        <div class="form-group">
          <label>Nome</label>
          <input v-model="editForm.firstName" class="field" />
        </div>
        <div class="form-group">
          <label>Sobrenome</label>
          <input v-model="editForm.lastName" class="field" />
        </div>
      </div>
      <div class="form-group">
        <label>E-mail</label>
        <input v-model="editForm.email" class="field" type="email" />
      </div>
      <p v-if="editError" class="error">{{ editError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showEdit = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="editing" @click="doEdit">Salvar</AppButton>
      </template>
    </AppModal>

    <!-- Reset senha -->
    <AppModal title="Redefinir Senha" :show="showReset" @close="showReset = false">
      <p class="text-sm text-muted" v-if="resetTarget">
        Usuário: <strong>{{ resetTarget.username }}</strong>
      </p>
      <div class="form-group">
        <label>Nova senha</label>
        <input v-model="resetForm.newPassword" class="field" type="text" placeholder="Mínimo 8 caracteres" />
      </div>
      <label class="checkbox">
        <input v-model="resetForm.temporary" type="checkbox" />
        <span>Marcar como temporária (usuário precisará trocar no próximo login)</span>
      </label>
      <p v-if="resetError" class="error">{{ resetError }}</p>
      <template #footer>
        <AppButton variant="ghost" @click="showReset = false">Cancelar</AppButton>
        <AppButton variant="primary" :loading="resetting" @click="doReset">Definir</AppButton>
      </template>
    </AppModal>
  </AppLayout>
</template>

<style scoped>
.header-actions { display: flex; gap: var(--space-2); align-items: center; }
.search-field { min-width: 260px; }

.tbl { width: 100%; border-collapse: collapse; }
.tbl th { font-size: var(--text-xs); text-transform: uppercase; letter-spacing: .5px; color: var(--text-muted); padding: 0 12px var(--space-3) 0; text-align: left; }
.tbl td { padding: var(--space-3) 12px var(--space-3) 0; border-top: 1px solid var(--border); vertical-align: middle; }
.cell-main { font-size: var(--text-sm); font-weight: 500; color: var(--text); }
.mono { font-family: var(--font-mono); }
.text-sm { font-size: var(--text-sm); }
.text-xs { font-size: var(--text-xs); }
.text-muted { color: var(--text-muted); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-6) 0; font-size: var(--text-sm); }

.row-actions { display: flex; gap: var(--space-1); justify-content: flex-end; flex-wrap: wrap; }

.form-group { display: flex; flex-direction: column; gap: var(--space-2); margin-bottom: var(--space-3); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: var(--space-3); }
.field { background: var(--panel); border: 1px solid var(--border); border-radius: var(--radius-md); padding: 8px 12px; font-size: var(--text-sm); color: var(--text); outline: none; width: 100%; box-sizing: border-box; }
.field:focus { border-color: var(--primary); }
.hint { font-size: var(--text-xs); color: var(--text-muted); margin: var(--space-1) 0 0 0; }
.req { color: var(--danger); }
.error { color: var(--danger); font-size: var(--text-sm); margin: var(--space-2) 0 0 0; }

.checkbox { display: flex; align-items: center; gap: var(--space-2); font-size: var(--text-sm); color: var(--text); cursor: pointer; margin-top: var(--space-2); }
.checkbox input { margin: 0; }
</style>
