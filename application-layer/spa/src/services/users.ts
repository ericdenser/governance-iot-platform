import api from './api'
import type { KeycloakUserDTO } from '@/types/models'

export const usersApi = {
  list: () => api.get<KeycloakUserDTO[]>('/users'),
}
