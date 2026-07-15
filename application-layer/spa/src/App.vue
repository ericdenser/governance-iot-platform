<script setup lang="ts">
import { watch } from 'vue'
import { RouterView } from 'vue-router'
import AppToast from '@/components/AppToast.vue'
import AppConfirm from '@/components/AppConfirm.vue'
import { useAuthStore } from '@/stores/auth'
import { useLiveStateStore } from '@/stores/liveState'

const authStore = useAuthStore()
const liveStore = useLiveStateStore()

// 1 EventSource global para o app inteiro; conecta ao logar, encerra ao deslogar
watch(
  () => authStore.isAuthenticated,
  (auth) => (auth ? liveStore.connect() : liveStore.disconnect()),
  { immediate: true },
)
</script>

<template>
  <main>
    <RouterView />
    <AppToast />
    <AppConfirm />
  </main>
</template>

<style scoped>
</style>
