import api from './api'

export const errorsApi = {
  list: (page = 0) => api.get(`/errors?page=${page}&size=20`),
}
