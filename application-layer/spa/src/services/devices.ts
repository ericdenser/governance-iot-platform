import api from './api'

export const devicesApi = {
  list: () => api.get('/devices'),
  get: (id: string) => api.get(`/devices/${id}`),
  getCommands: (id: string, page = 0) => api.get(`/devices/${id}/commands?page=${page}&size=10&sort=sentAt,desc`),
  getEvents: (id: string, page = 0) => api.get(`/devices/${id}/events?page=${page}&size=10`),
  getErrors: (id: string, page = 0) => api.get(`/devices/${id}/errors?page=${page}&size=10`),
  getCertificate: (id: string) => api.get(`/devices/${id}/certificate`),
  revoke: (id: string) => api.post(`/devices/${id}/revoke`),
}
