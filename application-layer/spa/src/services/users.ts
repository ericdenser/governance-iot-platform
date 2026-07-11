import api from './api'
import type {
  KeycloakUserDTO,
  CreateUserRequest,
  UpdateUserRequest,
  ResetPasswordRequest,
} from '@/types/models'

export const usersApi = {
  list: () => api.get<KeycloakUserDTO[]>('/users'),
  get: (userId: string) => api.get<KeycloakUserDTO>(`/users/${userId}`),
  create: (payload: CreateUserRequest) => api.post<KeycloakUserDTO>('/users', payload),
  update: (userId: string, payload: UpdateUserRequest) =>
    api.put<KeycloakUserDTO>(`/users/${userId}`, payload),
  delete: (userId: string) => api.delete(`/users/${userId}`),
  resetPassword: (userId: string, payload: ResetPasswordRequest) =>
    api.put(`/users/${userId}/reset-password`, payload),
}
