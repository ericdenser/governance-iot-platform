const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

export function isUuid(value: string | null | undefined): boolean {
  if (!value) return false
  return UUID_REGEX.test(value)
}

const MAC_REGEX = /^([0-9A-F]{2}[:-]){5}[0-9A-F]{2}$/i
export function isMac(value: string | null | undefined): boolean {
  if (!value) return false
  return MAC_REGEX.test(value)
}

const VERSION_REGEX = /^v?\d+(\.\d+){0,3}(-[a-z0-9]+)?$/i
export function isVersion(value: string | null | undefined): boolean {
  if (!value) return false
  return VERSION_REGEX.test(value)
}

export function isNonBlank(value: string | null | undefined): boolean {
  return !!value && value.trim().length > 0
}

export function firstError(checks: Array<[boolean, string]>): string | null {
  for (const [ok, msg] of checks) {
    if (!ok) return msg
  }
  return null
}
