import { defineStore } from 'pinia'

export interface DeviceLiveState {
  deviceId: string
  name?: string
  lastSeen?: string
  status?: string
  lat?: number
  lon?: number
  batteryMv?: number
  batteryTs?: string
  rssi?: number
  rssiTs?: string
  tempC?: number
  tempTs?: string
  health?: string
  healthReason?: string
}

export type SseConnectionStatus = 'disconnected' | 'connecting' | 'live'

// Fora do state: EventSource não deve ser envolvido pelo reactive() do Vue
let eventSource: EventSource | null = null

// EventSource desiste (CLOSED) quando recebe erro HTTP, ex: BFF reiniciando.
// Reabre com backoff ate 30s.
const RETRY_MIN_MS = 2000
const RETRY_MAX_MS = 30000
const OUTAGE_DELAY_MS = 5000
let retryDelay = RETRY_MIN_MS
let retryTimer: ReturnType<typeof setTimeout> | null = null
let outageTimer: ReturnType<typeof setTimeout> | null = null

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
    // true depois de OUTAGE_DELAY_MS sem conexao (o load inicial tambem passa por 'connecting')
    outage: false,
  }),

  actions: {
    setStatus(status: SseConnectionStatus) {
      this.connectionStatus = status
      if (status === 'live') {
        if (outageTimer) clearTimeout(outageTimer)
        outageTimer = null
        this.outage = false
      } else if (!outageTimer && !this.outage) {
        outageTimer = setTimeout(() => { outageTimer = null; this.outage = true }, OUTAGE_DELAY_MS)
      }
    },

    connect() {
      if (eventSource) return
      this.setStatus('connecting')

      eventSource = new EventSource('/api/realtime/stream?scope=map', {
        withCredentials: true
      })

      eventSource.addEventListener('connected', () => {
        retryDelay = RETRY_MIN_MS
        this.setStatus('live')
      })

      eventSource.addEventListener('device-live', (e: MessageEvent) => {
        try {
          const p = JSON.parse(e.data)
          if (!p.deviceId) return
          const prev = this.devices.get(p.deviceId)
          this.devices.set(p.deviceId, {
            ...prev,
            deviceId: p.deviceId,
            name: p.name ?? prev?.name,
            lastSeen: toIso(p.lastSeen) ?? prev?.lastSeen,
            status: p.status ?? prev?.status,
            lat: toNum(p.lat) ?? prev?.lat,
            lon: toNum(p.lon) ?? prev?.lon,
            batteryMv: toNum(p.batteryMv) ?? prev?.batteryMv,
            batteryTs: toIso(p.batteryTs) ?? prev?.batteryTs,
            rssi: toNum(p.rssi) ?? prev?.rssi,
            rssiTs: toIso(p.rssiTs) ?? prev?.rssiTs,
            tempC: toNum(p.tempC) ?? prev?.tempC,
            tempTs: toIso(p.tempTs) ?? prev?.tempTs,
            health: p.health ?? prev?.health,
            healthReason: p.healthReason ?? prev?.healthReason,
          })
        } catch {
          // payload inválido — ignora
        }
      })

      eventSource.onerror = () => {
        // CONNECTING: o proprio EventSource ja esta tentando de novo
        if (eventSource?.readyState !== EventSource.CLOSED) {
          this.setStatus('connecting')
          return
        }
        eventSource = null
        this.setStatus('disconnected')
        retryTimer = setTimeout(() => { retryTimer = null; this.connect() }, retryDelay)
        retryDelay = Math.min(retryDelay * 2, RETRY_MAX_MS)
      }
    },

    // Logout: encerra sem tentar reconectar e sem mostrar faixa
    disconnect() {
      if (retryTimer) clearTimeout(retryTimer)
      if (outageTimer) clearTimeout(outageTimer)
      retryTimer = null
      outageTimer = null
      retryDelay = RETRY_MIN_MS
      eventSource?.close()
      eventSource = null
      this.devices.clear()
      this.connectionStatus = 'disconnected'
      this.outage = false
    },
  },
})
