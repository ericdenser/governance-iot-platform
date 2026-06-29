import api from './api'

export const commandsApi = {
  list: (page = 0) => api.get(`/commands?page=${page}&size=15`),
  send: (payload: { command: string; targetDevices: string[]; params?: Record<string, unknown> }) =>
    api.post('/commands/send', payload),
}
