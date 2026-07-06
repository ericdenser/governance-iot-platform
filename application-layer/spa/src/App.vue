<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppToast from '@/components/AppToast.vue'
import AppConfirm from '@/components/AppConfirm.vue'
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
