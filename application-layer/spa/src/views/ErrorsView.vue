<script setup lang="ts">
import { ref, onMounted } from 'vue'
import AppLayout from '@/components/AppLayout.vue'
import AppCard from '@/components/AppCard.vue'
import AppPagination from '@/components/AppPagination.vue'
import ErrorList from '@/components/ErrorList.vue'
import FilterBar from '@/components/FilterBar.vue'
import { errorsApi, type ErrorFilters } from '@/services/errors'
import { statusCodes, statusText } from '@/utils/statusLabels'
import type { ErrorRecordResponseDTO } from '@/types/models'

// Device error codes (DeviceError enum in the govApi)
const ERROR_CODES = [
  'NVS_INIT_FAIL', 'NVS_WRITE_FAIL', 'NVS_COMMIT_FAIL', 'NVS_LOAD_FAIL', 'WIFI_TIMEOUT', 'TIME_SYNC_FAIL',
  'PROVISIONING_REQUEST_FAIL', 'PROVISIONING_RESPONSE_INVALID', 'MQTT_INIT_FAIL', 'MQTT_DISCONNECTED',
  'CRASH_ROLLBACK', 'OTA_FAIL', 'HTTP_INIT_FAIL', 'HTTP_REQUEST_FAIL', 'MEMORY_ALOCATION_FAIL',
  'KEY_GENERATION_FAIL', 'CSR_SUBJECT_NAME_FAIL', 'CSR_TO_PEM_FAIL', 'KEY_MISSING', 'CERT_MISSING',
  'TOPIC_SUBSCRIBE_FAIL', 'WATCHDOG_INIT_FAIL', 'WATCHDOG_ADD_FAIL', 'WATCHDOG_REMOVE_FAIL',
  'TOPIC_FORMAT_INVALID', 'COMMAND_RESPONSE_INVALID', 'DEVICE_ID_MISSING', 'WIFI_CREDENTIALS_MISSING',
  'FIRMWARE_VERSION_MISSING', 'FIRMWARE_ROLLBACK_FAILED', 'UNKNOWN',
]

const errors = ref<ErrorRecordResponseDTO[]>([])
const loading = ref(true)
const page = ref(0)
const totalPages = ref(1)
const filters = ref<ErrorFilters>({})
const code = ref('')
const status = ref('')
const statuses = statusCodes('error')

const load = async () => {
  const r = await errorsApi.list(page.value, { ...filters.value, code: code.value, status: status.value })
  errors.value = r.data.content ?? []
  totalPages.value = r.data.page?.totalPages ?? 1
}

const changePage = (p: number) => { page.value = p; load() }
const applyFilters = (f: ErrorFilters) => { filters.value = f; page.value = 0; load() }
const changeSelect = () => { page.value = 0; load() }

onMounted(async () => { try { await load() } finally { loading.value = false } })
</script>

<template>
  <AppLayout>
    <AppCard>
      <FilterBar class="filters" placeholder="Buscar por ID, dispositivo ou mensagem" @change="applyFilters">
        <select v-model="code" class="field" @change="changeSelect">
          <option value="">Todos os códigos</option>
          <option v-for="c in ERROR_CODES" :key="c" :value="c">{{ c }}</option>
        </select>
        <select v-model="status" class="field" @change="changeSelect">
          <option value="">Todos os status</option>
          <option v-for="s in statuses" :key="s" :value="s">{{ statusText('error', s) }}</option>
        </select>
      </FilterBar>

      <div v-if="loading" class="empty">Carregando...</div>
      <ErrorList v-else :errors="errors" />
      <AppPagination :page="page" :total-pages="totalPages" @change="changePage" />
    </AppCard>
  </AppLayout>
</template>

<style scoped>
.filters { margin-bottom: var(--space-5); }
.empty { text-align: center; color: var(--text-muted); padding: var(--space-8) 0; }
</style>
