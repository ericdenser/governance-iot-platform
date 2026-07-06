<script setup lang="ts">
import { onMounted, onBeforeUnmount } from 'vue'
import { useConfirm } from '@/composables/useConfirm'
import AppModal from './AppModal.vue'
import AppButton from './AppButton.vue'

const { active, respond } = useConfirm()

const onKeydown = (e: KeyboardEvent) => {
  if (e.key === 'Escape' && active.value) respond(false)
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <AppModal
    v-if="active"
    :show="!!active"
    :title="active.title"
    @close="respond(false)"
  >
    <p class="confirm-msg">{{ active.message }}</p>

    <template #footer>
      <AppButton variant="secondary" @click="respond(false)">
        {{ active.cancelText ?? 'Cancelar' }}
      </AppButton>
      <AppButton
        :variant="active.danger ? 'danger' : 'primary'"
        @click="respond(true)"
      >
        {{ active.confirmText ?? 'Confirmar' }}
      </AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.confirm-msg {
  color: var(--text-secondary);
  font-size: var(--text-sm);
  margin: 0;
  line-height: 1.5;
}
</style>
