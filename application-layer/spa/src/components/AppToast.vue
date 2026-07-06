<script setup lang="ts">
import { useToast } from '@/composables/useToast'

const { items, dismiss } = useToast()
</script>

<template>
  <div class="toast-container">
    <TransitionGroup name="toast">
      <div
        v-for="t in items"
        :key="t.id"
        :class="['toast', t.variant]"
        role="status"
        @click="dismiss(t.id)"
      >
        <span class="toast-msg">{{ t.message }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-container {
  position: fixed;
  top: var(--space-4);
  right: var(--space-4);
  z-index: 300;
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
  pointer-events: none;
  max-width: 400px;
}

.toast {
  pointer-events: auto;
  padding: var(--space-3) var(--space-4);
  border-radius: var(--radius-md);
  border: 1px solid;
  background: var(--surface);
  font-size: var(--text-sm);
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
  min-width: 240px;
}

.toast.success { border-color: var(--success); color: var(--success); background: var(--success-dim); }
.toast.warning { border-color: var(--warning); color: var(--warning); background: var(--warning-dim); }
.toast.error   { border-color: var(--danger);  color: var(--danger);  background: var(--danger-dim); }
.toast.info    { border-color: var(--primary); color: var(--primary); background: var(--primary-dim); }

.toast-msg { color: var(--text); }

.toast-enter-active,
.toast-leave-active { transition: all 0.25s ease; }
.toast-enter-from   { opacity: 0; transform: translateX(30px); }
.toast-leave-to     { opacity: 0; transform: translateX(30px); }
</style>
