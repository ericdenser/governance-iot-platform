import api from './api'
import type {
  FirmwareResponseDTO,
  FirmwareVersionResponseDTO,
  FirmwareVersionSummaryDTO,
  DeployableVersionProjection,
  CommandResultResponseDTO,
} from '@/types/models'

const upload = <T>(url: string, file: File, metadata: Record<string, unknown>) => {
  const form = new FormData()
  form.append('file', file)
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
  return api.post<T>(url, form)
}

export const firmwareApi = {
  // ── Firmware (product) ─────────────────────────────────────────────────────
  list: () => api.get<FirmwareResponseDTO[]>('/firmware'),
  get: (firmwareId: string) => api.get<FirmwareResponseDTO>(`/firmware/${firmwareId}`),
  create: (file: File, metadata: Record<string, unknown>) =>
    upload<FirmwareResponseDTO>('/firmware/create', file, metadata),
  setProvisioning: (firmwareId: string) => api.put<FirmwareResponseDTO>(`/firmware/${firmwareId}/provisioning`),

  // ── Firmware Version ───────────────────────────────────────────────────────
  listVersions: (firmwareId: string) => api.get<FirmwareVersionSummaryDTO[]>(`/firmware/${firmwareId}/versions`),
  getVersion: (versionId: string) => api.get<FirmwareVersionResponseDTO>(`/firmware/versions/${versionId}`),
  uploadVersion: (firmwareId: string, file: File, metadata: Record<string, unknown>) =>
    upload<FirmwareVersionResponseDTO>(`/firmware/${firmwareId}/upload`, file, metadata),
  deprecate: (versionId: string) => api.patch<FirmwareVersionResponseDTO>(`/firmware/versions/${versionId}/deprecate`),

  // ── Deploy ─────────────────────────────────────────────────────────────────
  listDeployable: () => api.get<DeployableVersionProjection[]>('/firmware/deployable'),
  deploy: (versionId: string, targetDevices: string[]) =>
    api.post<CommandResultResponseDTO>('/commands/send', {
      command: 'UPDATE',
      targetDevices,
      params: { versionId },
    }),

  // ── Flash package ──────────────────────────────────────────────────────────
  generatePackage: (payload: { deviceName: string; wifiSsid: string; wifiPass: string; groupId?: string }) =>
    api.post<Blob>('/devices/generate-package', payload, { responseType: 'blob' }),
}
