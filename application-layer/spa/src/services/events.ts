import api from './api'

export const eventsApi = {
  list: (page = 0) => api.get(`/events?page=${page}&size=20`),
}
