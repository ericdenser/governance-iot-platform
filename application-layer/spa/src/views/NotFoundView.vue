<script setup lang="ts">
import { useRoute } from 'vue-router'
import AppLayout from '@/components/AppLayout.vue'
import AppButton from '@/components/AppButton.vue'
import EmptyState from '@/components/EmptyState.vue'

const route = useRoute()
</script>

<template>
  <AppLayout>
    <EmptyState title="Página não encontrada"
                :hint="`O endereço ${route.path} não existe nesta plataforma. Ele pode ter sido digitado errado ou o item foi removido.`">
      <AppButton variant="primary" @click="$router.push('/home')">Ir para o Dashboard</AppButton>
      <AppButton variant="ghost" @click="$router.back()">Voltar</AppButton>
    </EmptyState>
  </AppLayout>
</template>
