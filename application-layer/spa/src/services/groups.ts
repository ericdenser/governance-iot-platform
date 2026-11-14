import api from './api'
import type {
  DeviceGroupResponseDTO,
  GroupMemberResponseDTO,
  GroupDeviceMemberDTO,
  GroupRole,
} from '@/types/models'

export const groupsApi = {
  // all: every group, myRole null where the user is not a member
  list: (all = false) => api.get<DeviceGroupResponseDTO[]>('/groups', { params: all ? { all: true } : undefined }),
  get: (id: string) => api.get<DeviceGroupResponseDTO>(`/groups/${id}`),
  create: (payload: { name: string; description?: string }) => api.post<DeviceGroupResponseDTO>('/groups', payload),
  delete: (id: string) => api.delete<void>(`/groups/${id}`),

  listDevices: (groupId: string) => api.get<GroupDeviceMemberDTO[]>(`/groups/${groupId}/devices`),
  addDevice: (groupId: string, deviceId: string) => api.put<void>(`/groups/${groupId}/devices/${deviceId}`),
  removeDevice: (groupId: string, deviceId: string) => api.delete<void>(`/groups/${groupId}/devices/${deviceId}`),
  checkMembership: (groupId: string, deviceId: string) =>
    api.get<{ member: boolean }>(`/groups/${groupId}/devices/${deviceId}/membership`),

  listUsers: (groupId: string) => api.get<GroupMemberResponseDTO[]>(`/groups/${groupId}/users`),
  assignUser: (groupId: string, payload: { keycloakUserId: string; role: GroupRole }) =>
    api.put<void>(`/groups/${groupId}/users`, payload),
  removeUser: (groupId: string, userId: string) => api.delete<void>(`/groups/${groupId}/users/${userId}`),
  updateRole: (groupId: string, userId: string, payload: { role: GroupRole }) =>
    api.patch<void>(`/groups/${groupId}/users/${userId}`, payload),
}
