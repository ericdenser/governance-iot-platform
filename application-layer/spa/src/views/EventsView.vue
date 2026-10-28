<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppPagination from '@/components/AppPagination.vue'
import EventList from '@/components/EventList.vue'
import FilterBar from '@/components/FilterBar.vue'
import { eventsApi, type EventFilters } from '@/services/events'
import { statusCodes, statusText } from '@/utils/statusLabels'
import type { EventRegistryResponseDTO } from '@/types/models'

const events = ref<EventRegistryResponseDTO[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)
const filters = ref<EventFilters>({})
const type = ref('')
const eventTypes = statusCodes('event')

const load = async () => {
  const r = await eventsApi.list(page.value, { ...filters.value, type: type.value })
  events.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}

const changePage = (p: number) => { page.value = p; load() }
// Any filter change goes back to the first page
const applyFilters = (f: EventFilters) => { filters.value = f; page.value = 0; load() }
const changeType = () => { page.value = 0; load() }

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard>
      <FilterBar class="filters" placeholder="Buscar por ID do evento ou dispositivo" @change="applyFilters">
        <select v-model="type" class="field" @change="changeType">
          <option value="">Todos os tipos</option>
          <option v-for="t in eventTypes" :key="t" :value="t">{{ statusText('event', t) }}</option>
        </select>
      </FilterBar>

      <div v-if="loading" class="empty">Carregando...</div>
      <EventList v-else :events="events" />
      <AppPagination :page="page" :total-pages="totalPages" @change="changePage" />
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.filters { margin-bottom: var(--space-5); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
</style>
