// Datas: listas mostram o relativo ("há 3 min") e o absoluto vai no title.

export const absoluteTime = (iso: string | null | undefined): string =>
  iso ? new Date(iso).toLocaleString('pt-BR') : '—'

export const relativeTime = (iso: string | null | undefined): string => {
  if (!iso) return '—'
  const t = new Date(iso).getTime()
  if (!Number.isFinite(t)) return '—'
  const s = Math.max(0, Math.floor((Date.now() - t) / 1000))
  if (s < 60) return 'agora'
  if (s < 3600) return `há ${Math.floor(s / 60)} min`
  if (s < 86400) return `há ${Math.floor(s / 3600)} h`
  if (s < 7 * 86400) return `há ${Math.floor(s / 86400)} d`
  return new Date(iso).toLocaleDateString('pt-BR')
}
