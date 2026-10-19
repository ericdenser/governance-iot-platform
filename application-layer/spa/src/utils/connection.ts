// Online = ultimo sinal ha no maximo 5 min
export const ONLINE_MAX_S = 5 * 60

export const isOnline = (lastSeen: string | null | undefined): boolean => {
  if (!lastSeen) return false
  const t = new Date(lastSeen).getTime()
  if (!Number.isFinite(t)) return false
  return (Date.now() - t) / 1000 <= ONLINE_MAX_S
}
