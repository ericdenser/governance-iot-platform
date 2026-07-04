import api from './api'
import type {
  DeviceSummaryDTO,
  DeviceDetailDTO,
  DeviceStatus,
  DeviceCertificateResponseDTO,
  CommandRecordResponseDTO,
  EventRegistryResponseDTO,
  ErrorRecordResponseDTO,
  Page,
} from '@/types/models'

interface ListParams {
  page?: number
  size?: number
  search?: string
  status?: DeviceStatus
}

export const devicesApi = {
  list: (params: ListParams = {}) => {
    const qs = new URLSearchParams()
    qs.set('page', String(params.page ?? 0))
    qs.set('size', String(params.size ?? 50))
    qs.set('sort', 'name')
    if (params.search) qs.set('search', params.search)
    if (params.status) qs.set('status', params.status)
    return api.get<Page<DeviceSummaryDTO>>(`/devices?${qs.toString()}`)
  },
  // Helper para pickers/wizards que precisam de todos os devices num único fetch.
  // TODO: substituir por busca server-side com paginação nos consumidores.
  listAll: async (): Promise<DeviceSummaryDTO[]> => {
    const r = await api.get<Page<DeviceSummaryDTO>>('/devices?page=0&size=1000&sort=name')
    return r.data.content
  },
  get: (id: string) => api.get<DeviceDetailDTO>(`/devices/${id}`),
  getCommands: (id: string, page = 0) =>
    api.get<Page<CommandRecordResponseDTO>>(`/devices/${id}/commands?page=${page}&size=10&sort=sentAt,desc`),
  getEvents: (id: string, page = 0) =>
    api.get<Page<EventRegistryResponseDTO>>(`/devices/${id}/events?page=${page}&size=10`),
  getErrors: (id: string, page = 0) =>
    api.get<Page<ErrorRecordResponseDTO>>(`/devices/${id}/errors?page=${page}&size=10`),
  getCertificate: (id: string) => api.get<DeviceCertificateResponseDTO>(`/devices/${id}/certificate`),
  revoke: (id: string) => api.post<void>(`/devices/${id}/revoke`),
}
