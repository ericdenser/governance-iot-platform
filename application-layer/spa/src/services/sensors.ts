import api from './api'

export const sensorsApi = {
  list: () => api.get('/sensors'),
  register: (name: string) => api.post('/sensors/register', { name }),
}
