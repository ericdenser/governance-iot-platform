import api from './api'

export const auditApi = {
  list: (page = 0) => api.get(`/audit?page=${page}&size=20`),
}
