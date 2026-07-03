<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppPagination from '@/components/AppPagination.vue'
import EventList from '@/components/EventList.vue'
import { eventsApi } from '@/services/events'
import type { EventRegistryResponseDTO } from '@/types/models'

const events = ref<EventRegistryResponseDTO[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)

const load = async () => {
  const r = await eventsApi.list(page.value)
  events.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}

const changePage = (p: number) => { page.value = p; load() }

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard title="Eventos" description="Histórico de eventos reportados pelos dispositivos">
      <div v-if="loading" class="empty">Carregando...</div>
      <EventList v-else :events="events" />
      <AppPagination :page="page" :total-pages="totalPages" @change="changePage" />
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
</style>
