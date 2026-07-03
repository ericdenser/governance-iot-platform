import api from './api'
import type { ErrorRecordResponseDTO, Page } from '@/types/models'

export const errorsApi = {
  list: (page = 0) => api.get<Page<ErrorRecordResponseDTO>>(`/errors?page=${page}&size=20&sort=reportedAt,desc`),
}
