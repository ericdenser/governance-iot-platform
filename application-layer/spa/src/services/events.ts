import api from './api'
import type { EventRegistryResponseDTO, Page } from '@/types/models'

export interface EventFilters { q?: string; type?: string; from?: string; to?: string }

// Empty strings would still be sent as ?q= ; drop them
const clean = <T extends object>(f: T) =>
  Object.fromEntries(Object.entries(f).filter(([, v]) => v !== undefined && v !== ''))

export const eventsApi = {
  list: (page = 0, filters: EventFilters = {}) =>
    api.get<Page<EventRegistryResponseDTO>>('/events', { params: { page, size: 20, ...clean(filters) } }),
}

export { clean }
