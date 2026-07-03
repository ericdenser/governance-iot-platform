import api from './api'
import type { AuditLogResponseDTO, Page } from '@/types/models'

export const auditApi = {
  list: (page = 0) => api.get<Page<AuditLogResponseDTO>>(`/audit?page=${page}&size=20`),
}
