import api from './api'
import { clean } from './events'
import type { ErrorRecordResponseDTO, Page } from '@/types/models'

export interface ErrorFilters { q?: string; code?: string; status?: string; from?: string; to?: string }

export const errorsApi = {
  list: (page = 0, filters: ErrorFilters = {}) =>
    api.get<Page<ErrorRecordResponseDTO>>('/errors', { params: { page, size: 20, sort: 'reportedAt,desc', ...clean(filters) } }),
}
