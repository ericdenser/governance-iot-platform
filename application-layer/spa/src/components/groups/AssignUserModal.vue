<script setup lang="ts">
import { ref, computed } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'
import AppSkeleton from '@/components/AppSkeleton.vue'
import EmptyState from '@/components/EmptyState.vue'
import { groupsApi } from '@/services/groups'
import { errorMessage } from '@/utils/errors'
import type { GroupRole, KeycloakUserDTO } from '@/types/models'

const props = defineProps<{
  groupId: string
  users: KeycloakUserDTO[]
  loading: boolean
  excludeIds: Set<string>
  roles: GroupRole[]
}>()

const emit = defineEmits<{ (e: 'close' | 'assigned'): void }>()

const search = ref('')
const selectedId = ref('')
const role = ref<GroupRole>('MEMBER')
const assigning = ref(false)
const error = ref('')

const pickable = computed(() => {
  const q = search.value.toLowerCase()
  return props.users
    .filter(u => !props.excludeIds.has(u.keycloakUserId))
    .filter(u => !q || u.username?.toLowerCase().includes(q) || u.email?.toLowerCase().includes(q))
})

const assign = async () => {
  if (!selectedId.value) { error.value = 'Selecione um usuário.'; return }
  assigning.value = true; error.value = ''
  try {
    await groupsApi.assignUser(props.groupId, { keycloakUserId: selectedId.value, role: role.value })
    emit('assigned')
  } catch (e: unknown) {
    error.value = errorMessage(e, 'Erro ao atribuir usuário.')
  } finally { assigning.value = false }
}
</script>

<template>
  <AppModal title="Atribuir Usuário" @close="emit('close')">
    <input v-model="search" class="field" placeholder="Buscar por nome ou e-mail..." aria-label="Buscar usuário" />
    <AppSkeleton v-if="loading" variant="list" :rows="4" />
    <EmptyState v-else-if="!pickable.length" compact
      :title="users.length ? 'Todos os usuários já estão no grupo' : 'Nenhum usuário encontrado'" />
    <div v-else class="picker">
      <button
        v-for="u in pickable" :key="u.keycloakUserId" type="button"
        class="picker-row" :class="{ selected: selectedId === u.keycloakUserId }"
        :aria-pressed="selectedId === u.keycloakUserId"
        @click="selectedId = u.keycloakUserId"
      >
        <span class="picker-name">{{ u.username }}</span>
        <span class="picker-id">{{ u.email }}</span>
      </button>
    </div>
    <div class="form-group">
      <label for="groups-f3">Papel</label>
      <select id="groups-f3" v-model="role" class="field">
        <option v-for="r in roles" :key="r" :value="r">{{ r }}</option>
      </select>
    </div>
    <p v-if="error" class="field-error">{{ error }}</p>
    <template #footer>
      <AppButton variant="ghost" @click="emit('close')">Cancelar</AppButton>
      <AppButton variant="primary" :loading="assigning" :disabled="!selectedId" @click="assign">Atribuir</AppButton>
    </template>
  </AppModal>
</template>

<style scoped src="./picker.css"></style>
