import api from './api'
import type {
  CommandRecordResponseDTO,
  CommandRequest,
  CommandResultResponseDTO,
  Page,
} from '@/types/models'

export const commandsApi = {
  list: (page = 0) => api.get<Page<CommandRecordResponseDTO>>(`/commands?page=${page}&size=15`),
  send: (payload: CommandRequest) => api.post<CommandResultResponseDTO>('/commands/send', payload),
}
