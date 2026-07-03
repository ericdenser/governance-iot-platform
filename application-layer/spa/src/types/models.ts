// Interfaces TypeScript espelhando os DTOs do govApi.

// ── Enums (string unions, compatível com Jackson serialization) ──────────────
export type FirmwareStatus = 'STAGED' | 'DEPLOYED' | 'DEPRECATED'
export type DeviceStatus =
  | 'PENDING'
  | 'PROVISIONING'
  | 'ACTIVE'
  | 'COMMAND_PENDING'
  | 'REVOKED'
  | 'ERROR'
export type CommandStatus = 'PENDING' | 'COMPLETED_SUCCESS' | 'FAILED' | 'TIMEOUT'
export type DeviceCommands = 'UPDATE' | 'REBOOT' | 'DEEP_SLEEP' | 'FIRMWARE_ROLLBACK'
export type ErrorStatus = 'PENDING' | 'FIXED' | 'RETRY_FAILED' | 'NOT_FIXABLE'
export type GroupRole = 'VIEWER' | 'MEMBER' | 'OWNER'
export type DeviceError =
  | 'OTA_FAIL'
  | 'SENSOR_FAILURE'
  | 'PROVISIONING_FAILED'
  | 'WIFI_FAILURE'
  | (string & {}) // fallback pra enums que evoluem no backend

// ── Envelope ApiResponse ────────────────────────────────────────────────────
export interface ApiResponse<T> {
  data: T
  path: string
  timestamp?: string
}

// ── Firmware ────────────────────────────────────────────────────────────────
export interface FirmwareVersionSummaryDTO {
  versionId: string
  version: string
  status: FirmwareStatus
  uploadedAt: string
  createdByUsername: string | null
  deployCount: number
  sizeBytes: number
}

export interface FirmwareResponseDTO {
  firmwareId: string
  firmwareName: string
  description: string | null
  ownerGroupId: string | null
  provisioningFirmware: boolean
  createdAt: string
  createdByActorId: string | null
  createdByUsername: string | null
  versionsCount: number
  latestVersion: FirmwareVersionSummaryDTO | null
}

export interface FirmwareSensorConfigResponseDTO {
  sensorName: string
  pin: number
}

export interface FirmwareVersionResponseDTO {
  versionId: string
  firmwareId: string
  firmwareName: string
  ownerGroupId: string | null
  provisioningFirmware: boolean
  version: string
  filename: string
  originalFilename: string
  sha256: string
  sizeBytes: number
  downloadUrl: string
  releaseNotes: string | null
  status: FirmwareStatus
  sensorConfigs: FirmwareSensorConfigResponseDTO[]
  uploadedAt: string
  deployCount: number
  createdByActorId: string | null
  createdByUsername: string | null
}

export interface FirmwareSummaryDTO {
  firmwareId: string
  firmwareName: string
  isProvisioning: boolean
}

export interface DeployableVersionProjection {
  versionId: string
  version: string
  firmwareId: string
  firmwareName: string
  ownerGroupId: string | null
  status: FirmwareStatus
  uploadedAt: string
}

// ── Firmware — requests ─────────────────────────────────────────────────────
export interface SensorConfigDTO {
  sensorId: string
  pin: number
}

export interface CreateFirmwareRequest {
  firmwareName: string
  description?: string | null
  initialVersion: string
  isProvisioning: boolean
  ownerGroupId?: string | null
  sensors: SensorConfigDTO[]
}

export interface UploadVersionRequest {
  version: string
  releaseNotes?: string | null
  sensors: SensorConfigDTO[]
}

// ── Device ──────────────────────────────────────────────────────────────────
export interface DeviceSummaryDTO {
  deviceId: string
  name: string
  status: DeviceStatus
  macAddress: string | null
  firmwareId: string | null
  firmwareName: string | null
  firmwareVersionId: string | null
  firmwareVersion: string | null
  createdAt: string
  lastSeen: string | null
  issuedByActorId: string | null
  issuedByUsername: string | null
}

export interface DeviceDetailDTO {
  deviceId: string
  name: string
  status: DeviceStatus
  macAddress: string | null
  createdAt: string
  lastSeen: string | null
  firmware: FirmwareSummaryDTO | null
  firmwareVersion: FirmwareVersionSummaryDTO | null
  sensorStatus: Record<string, boolean>
  issuedByActorId: string | null
  issuedByUsername: string | null
}

export interface DeviceCertificateResponseDTO {
  deviceId: string
  serialNumber: string
  issuedAt: string
  expiresAt: string
  revokedAt: string | null
}

// ── Group ───────────────────────────────────────────────────────────────────
export interface DeviceGroupResponseDTO {
  groupId: string
  name: string
  description: string | null
  createdByActorId: string | null
  createdByUsername: string | null
  createdAt: string
  myRole: GroupRole | null
}

export interface GroupMemberResponseDTO {
  keycloakUserId: string
  role: GroupRole
  assignedByActorId: string | null
  assignedByUsername: string | null
  assignedAt: string
}

export interface GroupDeviceMemberDTO {
  deviceId: string
  name: string
  status: DeviceStatus
  addedByActorId: string | null
  addedByUsername: string | null
  assignedAt: string
}

// ── Command / Event / Error ─────────────────────────────────────────────────
export interface CommandRecordResponseDTO {
  commandId: string
  commandType: DeviceCommands
  status: CommandStatus
  sentAt: string
  payload: string | null
  completedAt: string | null
  errorMessage: string | null
  deviceId: string | null
  deviceName: string | null
  createdByActorId: string | null
  createdByUsername: string | null
}

export interface CommandResultResponseDTO {
  command: string
  publishedTo: string[]
  failed: string[]
  skipped: string[]
}

export interface CommandRequest {
  command: DeviceCommands
  targetDevices: string[]
  params?: Record<string, unknown>
}

export interface EventRegistryResponseDTO {
  eventId: string
  eventType: string
  deviceId: string | null
  deviceName: string | null
  previousStatus: string | null
  newStatus: string | null
  completed: boolean
  resultMessage: string | null
  occurredAt: string
}

export interface ErrorRecordResponseDTO {
  errorId: string
  deviceId: string | null
  deviceName: string | null
  error: DeviceError
  status: ErrorStatus
  reportedAt: string
  message: string | null
  details: string | null
  fixedAt: string | null
}

// ── Sensors ─────────────────────────────────────────────────────────────────
export interface SensorResponseDTO {
  sensorId: string
  name: string
  createdAt: string
}

// ── Audit ───────────────────────────────────────────────────────────────────
export interface AuditLogResponseDTO {
  auditId: string
  actorId: string | null
  actorUsername: string | null
  action: string
  targetType: string | null
  targetId: string | null
  details: string | null
  success: boolean
  errorMessage: string | null
  performedAt: string
}

// ── Auth / User ─────────────────────────────────────────────────────────────
export interface MeResponse {
  authenticated: boolean
  nome: string | null
  username: string | null
  keycloakUserId: string | null
}

export interface CheckRoleResponse {
  hasRole: boolean
}

export interface KeycloakUserDTO {
  keycloakUserId: string
  username: string
  email: string | null
}

// ── Spring Data Page envelope ───────────────────────────────────────────────
export interface Page<T> {
  content: T[]
  page: {
    size: number
    number: number
    totalElements: number
    totalPages: number
  }
}
