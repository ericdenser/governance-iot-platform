import api from './api'

const upload = (url: string, file: File, metadata: Record<string, any>) => {
  const form = new FormData()
  form.append('file', file)
  form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
  return api.post(url, form)
}

export const firmwareApi = {
  // ── Firmware (product) ─────────────────────────────────────────────────────
  list: () => api.get('/firmware'),
  get: (firmwareId: string) => api.get(`/firmware/${firmwareId}`),
  create: (file: File, metadata: Record<string, any>) => upload('/firmware/create', file, metadata),
  setProvisioning: (firmwareId: string) => api.put(`/firmware/${firmwareId}/provisioning`),

  // ── Firmware Version ───────────────────────────────────────────────────────
  listVersions: (firmwareId: string) => api.get(`/firmware/${firmwareId}/versions`),
  getVersion: (versionId: string) => api.get(`/firmware/versions/${versionId}`),
  uploadVersion: (firmwareId: string, file: File, metadata: Record<string, any>) =>
    upload(`/firmware/${firmwareId}/upload`, file, metadata),
  deprecate: (versionId: string) => api.patch(`/firmware/versions/${versionId}/deprecate`),

  // ── Deploy ─────────────────────────────────────────────────────────────────
  listDeployable: () => api.get('/firmware/deployable'),
  deploy: (versionId: string, targetDevices: string[]) =>
    api.post('/commands/send', { command: 'UPDATE', targetDevices, params: { versionId } }),

  // ── Flash package ──────────────────────────────────────────────────────────
  generatePackage: (payload: { deviceName: string; wifiSsid: string; wifiPass: string; groupId?: string }) =>
    api.post('/devices/generate-package', payload, { responseType: 'blob' }),
}
