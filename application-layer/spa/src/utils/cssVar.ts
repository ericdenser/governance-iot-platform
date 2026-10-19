// Le um token do design-system.css ja resolvido (pra libs que pedem cor em string, ex: Chart.js).
export const cssVar = (name: string, fallback = ''): string =>
  getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback
