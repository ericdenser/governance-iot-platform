import api from './api'
import type {
  CommandBatchDTO,
  CommandRecordResponseDTO,
  CommandRequest,
  CommandResultResponseDTO,
  Page,
} from '@/types/models'

export const commandsApi = {
  list: (page = 0) => api.get<Page<CommandBatchDTO>>(`/commands?page=${page}&size=15`),
  records: (batchId: string) => api.get<CommandRecordResponseDTO[]>(`/commands/${batchId}`),
  send: (payload: CommandRequest) => api.post<CommandResultResponseDTO>('/commands/send', payload),
}
