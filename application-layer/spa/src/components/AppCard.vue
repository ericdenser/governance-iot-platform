<script setup lang="ts">
defineProps<{
  title?: string
  description?: string
  noPadding?: boolean
}>()
</script>

<template>
  <div class="card">
    <div v-if="title || description" class="card-header">
      <div>
        <h3 v-if="title" class="card-title">{{ title }}</h3>
        <p v-if="description" class="card-description">{{ description }}</p>
      </div>
      <slot name="actions" />
    </div>
    <div class="card-body" :class="{ 'no-padding': noPadding }">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.card {
  background: var(--surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  overflow: hidden;
}

.card-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-4);
  padding: var(--space-5) var(--space-5) 0;
}

.card-title {
  font-family: var(--font-sans);
  font-size: var(--text-md);
  font-weight: 600;
  color: var(--text);
  margin-bottom: var(--space-1);
}

.card-description {
  font-size: var(--text-sm);
  color: var(--text-muted);
}

.card-body {
  padding: var(--space-5);
}

.card-body.no-padding {
  padding: 0;
}
</style>
