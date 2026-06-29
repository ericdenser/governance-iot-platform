<script setup lang="ts">
import { computed } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppBadge from '@/components/AppBadge.vue'
import { useAuthStore } from '@/stores/auth'

const authStore = useAuthStore()
const user = computed(() => authStore.user)
const isAdmin = computed(() => authStore.isAdmin)

const initials = computed(() => {
  const name = user.value?.nome ?? user.value?.username ?? '?'
  return name.split(' ').map((w: string) => w[0]).slice(0, 2).join('').toUpperCase()
})
</script>

<template>
  <AppLayout>
    <AppCard title="Meu Perfil">
      <div class="profile">
        <div class="avatar">{{ initials }}</div>
        <div class="info">
          <div class="info-row">
            <span class="label">Nome</span>
            <span class="value">{{ user?.nome ?? '—' }}</span>
          </div>
          <div class="info-row">
            <span class="label">Username</span>
            <span class="value mono">{{ user?.username ?? '—' }}</span>
          </div>
          <div class="info-row">
            <span class="label">Perfil</span>
            <AppBadge :variant="isAdmin ? 'primary' : 'muted'">
              {{ isAdmin ? 'Administrador' : 'Usuário' }}
            </AppBadge>
          </div>
        </div>
      </div>
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.profile { display: flex; align-items: flex-start; gap: var(--space-6); padding: var(--space-2) 0; }
.avatar { width: 64px; height: 64px; border-radius: 50%; background: var(--primary); color: #fff; font-size: var(--text-xl); font-weight: 700; display: flex; align-items: center; justify-content: center; flex-shrink: 0; }
.info { display: flex; flex-direction: column; gap: 0; flex: 1; }
.info-row { display: flex; align-items: center; padding: var(--space-3) 0; border-bottom: 1px solid var(--border); gap: var(--space-4); }
.info-row:last-child { border-bottom: none; }
.label { width: 140px; font-size: var(--text-sm); color: var(--text-muted); flex-shrink: 0; }
.value { font-size: var(--text-sm); color: var(--text); }
.mono { font-family: var(--font-mono); }
</style>
