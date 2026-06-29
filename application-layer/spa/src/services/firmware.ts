import api from './api'

export const firmwareApi = {
  list: () => api.get('/firmware'),
  get: (id: string) => api.get(`/firmware/${id}`),
  upload: (file: File, metadata: Record<string, any>) => {
    const form = new FormData()
    form.append('file', file)
    form.append('metadata', new Blob([JSON.stringify(metadata)], { type: 'application/json' }))
    return api.post('/firmware/upload', form)
  },
  deploy: (firmwareId: string, targetDevices: string[]) =>
    api.post('/commands/send', { command: 'UPDATE', targetDevices, params: { firmwareId } }),
  deprecate: (id: string) => api.patch(`/firmware/${id}/deprecate`),
  setProvisioning: (id: string) => api.put(`/firmware/${id}/provisioning`),
  generatePackage: (payload: { deviceName: string }) =>
    api.post('/devices/generate-package', payload, { responseType: 'blob' }),
}
