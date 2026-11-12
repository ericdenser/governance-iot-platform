<script setup lang="ts">
import { ref } from 'vue'
import AppModal from '@/components/AppModal.vue'
import AppButton from '@/components/AppButton.vue'

defineProps<{ targetCount: number }>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'confirm', seconds: number): void
}>()

const MIN_S = 10
const MAX_S = 259200

const durationS = ref(300)
const error = ref('')

const confirm = () => {
  const v = Number(durationS.value)
  if (!Number.isFinite(v) || v < MIN_S || v > MAX_S) {
    error.value = `Duração deve estar entre ${MIN_S} e ${MAX_S} segundos (3 dias).`
    return
  }
  emit('confirm', v)
}
</script>

<template>
  <AppModal size="md" title="Duração — DEEP_SLEEP" @close="emit('close')">
    <p class="modal-hint mono">{{ targetCount }} device(s) alvo</p>

    <div class="form-group">
      <label for="commands-f1">Duração <span class="text-muted">(segundos)</span></label>
      <input id="commands-f1" v-model.number="durationS" type="number" :min="MIN_S" :max="MAX_S" class="field duration" placeholder="ex: 300" @keydown.enter="confirm" />
      <span class="field-hint mono">Mínimo {{ MIN_S }}s · Máximo {{ MAX_S }}s (3 dias)</span>
    </div>
    <p v-if="error" class="field-error">{{ error }}</p>

    <template #footer>
      <AppButton variant="ghost" @click="emit('close')">Cancelar</AppButton>
      <AppButton variant="primary" @click="confirm">Enviar DEEP_SLEEP</AppButton>
    </template>
  </AppModal>
</template>

<style scoped>
.modal-hint { font-size: var(--text-xs); color: var(--text-muted); margin: 0; }
.form-group { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group label { font-size: var(--text-sm); color: var(--text-muted); }
.field.duration { width: auto; font-family: var(--font-mono); }
.field-hint { font-size: var(--text-xs); color: var(--text-muted); }
</style>
