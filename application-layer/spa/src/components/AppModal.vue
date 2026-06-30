<script setup lang="ts">
defineProps<{
  show: boolean
  title: string
  wide?: boolean
}>()

defineEmits<{ (e: 'close'): void }>()
</script>

<template>
  <div v-if="show" class="modal-overlay" @click.self="$emit('close')">
    <div class="modal" :class="{ 'modal-wide': wide }">
      <h3 class="modal-title">{{ title }}</h3>
      <slot />
      <div v-if="$slots.footer" class="modal-footer">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: 200; }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 440px; max-width: 94vw; max-height: 85vh; display: flex; flex-direction: column; gap: var(--space-4); overflow-y: auto; }
.modal-wide { width: 640px; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }
</style>
