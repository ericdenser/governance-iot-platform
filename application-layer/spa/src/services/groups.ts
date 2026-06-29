import api from './api'

export const groupsApi = {
  list: () => api.get('/groups'),
  get: (id: string) => api.get(`/groups/${id}`),
  create: (payload: { name: string; description?: string }) => api.post('/groups', payload),
  delete: (id: string) => api.delete(`/groups/${id}`),

  listDevices: (groupId: string) => api.get(`/groups/${groupId}/devices`),
  addDevice: (groupId: string, deviceId: string) => api.put(`/groups/${groupId}/devices/${deviceId}`),
  removeDevice: (groupId: string, deviceId: string) => api.delete(`/groups/${groupId}/devices/${deviceId}`),
  checkMembership: (groupId: string, deviceId: string) =>
    api.get(`/groups/${groupId}/devices/${deviceId}/membership`),

  listUsers: (groupId: string) => api.get(`/groups/${groupId}/users`),
  assignUser: (groupId: string, payload: { keycloakUserId: string; role: string }) =>
    api.put(`/groups/${groupId}/users`, payload),
  removeUser: (groupId: string, userId: string) => api.delete(`/groups/${groupId}/users/${userId}`),
}
