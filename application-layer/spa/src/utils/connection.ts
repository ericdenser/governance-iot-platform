import { ref } from 'vue'

// Online = ultimo sinal ha no maximo 5 min
export const ONLINE_MAX_S = 5 * 60

// Reactive clock: computeds that call isOnline() re-run as time passes, so a
// device that stops reporting turns offline without a new SSE message
const now = ref(Date.now())
setInterval(() => { now.value = Date.now() }, 30_000)

export const isOnline = (lastSeen: string | null | undefined): boolean => {
  if (!lastSeen) return false
  const t = new Date(lastSeen).getTime()
  if (!Number.isFinite(t)) return false
  return (now.value - t) / 1000 <= ONLINE_MAX_S
}

export type ConnState = 'online' | 'offline' | 'pending'
export const CONN_STATES: ConnState[] = ['online', 'offline', 'pending']

// Same rule on every screen: not activated yet = pending, revoked never counts as online
export const connState = (status: string | null | undefined, lastSeen: string | null | undefined): ConnState => {
  if (status === 'PENDING' || status === 'PROVISIONING') return 'pending'
  if (status === 'REVOKED') return 'offline'
  return isOnline(lastSeen) ? 'online' : 'offline'
}
