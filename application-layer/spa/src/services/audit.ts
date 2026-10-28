import api from './api'
import { clean } from './events'
import type { AuditLogResponseDTO, Page } from '@/types/models'

export interface AuditFilters { q?: string; action?: string; from?: string; to?: string }

export const auditApi = {
  list: (page = 0, filters: AuditFilters = {}) =>
    api.get<Page<AuditLogResponseDTO>>('/audit', { params: { page, size: 20, ...clean(filters) } }),
}
