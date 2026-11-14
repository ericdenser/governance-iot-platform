import api from './api'
import type { SensorResponseDTO } from '@/types/models'

export const sensorsApi = {
  list: () => api.get<SensorResponseDTO[]>('/sensors'),
  register: (code: string, name?: string) => api.post<SensorResponseDTO>('/sensors/register', { code, name }),
  rename: (sensorId: string, name: string) => api.patch<SensorResponseDTO>(`/sensors/${sensorId}`, { name }),
}
