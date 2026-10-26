import api from './api'
import type { TelemetryPointDTO } from '@/types/models'

export const telemetryApi = {
  series: (deviceId: string, minutes: number, resolution: string) =>
    api.get<TelemetryPointDTO[]>(`/telemetry/${encodeURIComponent(deviceId)}`, { params: { minutes, resolution } }),
}
