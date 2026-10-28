// The SPA may be served under a sub-path (VITE_BASE_PATH, e.g. /governance/).
// BASE always ends with "/".
export const BASE = import.meta.env.BASE_URL

// "api/logout" or "/api/logout" -> "/governance/api/logout"
export const withBase = (path: string): string => BASE + path.replace(/^\//, '')

// "/governance/devices/x" -> "/devices/x" (router paths have no base)
export const stripBase = (pathname: string): string =>
  pathname.startsWith(BASE) ? '/' + pathname.slice(BASE.length) : pathname
