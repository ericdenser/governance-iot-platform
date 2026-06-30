<script setup lang="ts">
import AppButton from '@/components/AppButton.vue'

const props = defineProps<{
  page: number
  totalPages: number
}>()

const emit = defineEmits<{
  (e: 'change', page: number): void
}>()
</script>

<template>
  <div class="pagination">
    <AppButton size="sm" variant="secondary" :disabled="props.page === 0"
      @click="emit('change', props.page - 1)">Anterior</AppButton>
    <span class="text-muted text-sm">Página {{ props.page + 1 }} de {{ props.totalPages }}</span>
    <AppButton size="sm" variant="secondary" :disabled="props.page + 1 >= props.totalPages"
      @click="emit('change', props.page + 1)">Próxima</AppButton>
  </div>
</template>

<style scoped>
.pagination { display: flex; align-items: center; justify-content: center; gap: var(--space-4); margin-top: var(--space-4); }
.text-sm { font-size: var(--text-sm); }
.text-muted { color: var(--text-muted); }
</style>
