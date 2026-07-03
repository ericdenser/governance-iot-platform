import api from './api'
import type { EventRegistryResponseDTO, Page } from '@/types/models'

export const eventsApi = {
  list: (page = 0) => api.get<Page<EventRegistryResponseDTO>>(`/events?page=${page}&size=20`),
}
