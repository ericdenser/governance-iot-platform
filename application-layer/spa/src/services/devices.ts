import api from './api'
import type {
  DeviceSummaryDTO,
  DeviceDetailDTO,
  DeviceCertificateResponseDTO,
  CommandRecordResponseDTO,
  EventRegistryResponseDTO,
  ErrorRecordResponseDTO,
  Page,
} from '@/types/models'

export const devicesApi = {
  list: () => api.get<DeviceSummaryDTO[]>('/devices'),
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
