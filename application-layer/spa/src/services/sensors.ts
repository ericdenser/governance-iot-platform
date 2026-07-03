import api from './api'
import type { SensorResponseDTO } from '@/types/models'

export const sensorsApi = {
  list: () => api.get<SensorResponseDTO[]>('/sensors'),
  register: (name: string) => api.post<SensorResponseDTO>('/sensors/register', { name }),
}
