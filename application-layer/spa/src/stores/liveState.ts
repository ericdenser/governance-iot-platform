import { defineStore } from 'pinia'

export interface DeviceLiveState {
  deviceId: string
  lastSeen?: string
  status?: string
  lat?: number
  lon?: number
}

export type SseConnectionStatus = 'disconnected' | 'connecting' | 'live'

// Fora do state: EventSource não deve ser envolvido pelo reactive() do Vue
let eventSource: EventSource | null = null

// last_seen vem do hash como epoch millis em string; DTOs REST usam ISO.
// Normaliza pra ISO pra que os fmt() das views funcionem com ambos.
const toIso = (v: unknown): string | undefined => {
  if (v == null) return undefined
  const s = String(v)
  if (/^\d+$/.test(s)) return new Date(Number(s)).toISOString()
  return s
}

const toNum = (v: unknown): number | undefined => {
  if (v == null) return undefined
  const n = Number(v)
  return Number.isFinite(n) ? n : undefined
}

export const useLiveStateStore = defineStore('liveState', {
  state: () => ({
    devices: new Map<string, DeviceLiveState>(),
    connectionStatus: 'disconnected' as SseConnectionStatus,
  }),

  actions: {
    connect() {
      if (eventSource) return
      this.connectionStatus = 'connecting'

      eventSource = new EventSource('/api/realtime/stream?scope=map', {
        withCredentials: true
      })

      eventSource.addEventListener('connected', () => {
        this.connectionStatus = 'live'
      })

      eventSource.addEventListener('device-live', (e: MessageEvent) => {
        try {
          const p = JSON.parse(e.data)
          if (!p.deviceId) return
          const prev = this.devices.get(p.deviceId)
          this.devices.set(p.deviceId, {
            ...prev,
            deviceId: p.deviceId,
            lastSeen: toIso(p.lastSeen) ?? prev?.lastSeen,
            status: p.status ?? prev?.status,
            lat: toNum(p.lat) ?? prev?.lat,
            lon: toNum(p.lon) ?? prev?.lon,
          })
        } catch {
          // payload inválido — ignora
        }
      })

      eventSource.onerror = () => {
        // EventSource reconecta sozinho; CLOSED significa que desistiu
        this.connectionStatus =
          eventSource?.readyState === EventSource.CLOSED ? 'disconnected' : 'connecting'
      }
    },

    disconnect() {
      eventSource?.close()
      eventSource = null
      this.devices.clear()
      this.connectionStatus = 'disconnected'
    },
  },
})
