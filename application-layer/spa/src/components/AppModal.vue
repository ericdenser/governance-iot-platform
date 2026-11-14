<script setup lang="ts">
withDefaults(defineProps<{
  show?: boolean
  title?: string
  wide?: boolean
  size?: 'sm' | 'md' | 'lg'
}>(), { show: true, size: 'sm' })

const emit = defineEmits<{ (e: 'close'): void }>()
</script>

<template>
  <div v-if="show" v-modal-dialog="() => emit('close')" class="modal-overlay" @click.self="emit('close')">
    <div class="modal" :class="[`modal-${wide ? 'lg' : size}`]">
      <h3 class="modal-title"><slot name="title">{{ title }}</slot></h3>
      <slot />
      <div v-if="$slots.footer" class="modal-footer">
        <slot name="footer" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.modal-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.6); display: flex; align-items: center; justify-content: center; z-index: var(--z-modal); }
.modal { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-lg); padding: var(--space-6); width: 440px; max-width: 94vw; max-height: 85vh; max-height: 85dvh; display: flex; flex-direction: column; gap: var(--space-4); overflow-y: auto; }
.modal-md { width: 520px; }
.modal-lg { width: 640px; }
.modal-title { font-size: var(--text-lg); font-weight: 600; color: var(--text); margin: 0; }
.modal-footer { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: var(--space-2); padding-top: var(--space-2); border-top: 1px solid var(--border); margin-top: auto; }

@media (max-width: 540px) {
  .modal { padding: var(--space-4); }
}
</style>
