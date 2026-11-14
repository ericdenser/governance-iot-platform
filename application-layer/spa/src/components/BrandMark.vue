<script setup lang="ts">
// Typographic brand: full wordmark, or the "G" monogram when space is tight
defineProps<{ compact?: boolean }>()
</script>

<template>
  <span v-if="compact" class="monogram" aria-label="Governance IoT">G</span>
  <span v-else class="wordmark">Governance <span class="iot">IoT</span></span>
</template>

<style scoped>
.wordmark {
  font-family: var(--font-sans);
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--text);
  white-space: nowrap;
}
.iot {
  font-family: var(--font-mono);
  font-weight: 500;
  color: var(--accent);
}
.monogram {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 1px solid var(--border-strong);
  border-radius: var(--radius-sm);
  font-family: var(--font-mono);
  font-weight: 600;
  font-size: var(--text-sm);
  color: var(--text);
}
</style>
