import type { ApiErrorResponse } from '@/types/errors'

type AxiosLike = {
  response?: { data?: ApiErrorResponse | { error?: ApiErrorResponse['error'] } | unknown }
  message?: string
}

function extractError(e: unknown): ApiErrorResponse['error'] | null {
  const err = e as AxiosLike
  const data = err?.response?.data as { error?: ApiErrorResponse['error'] } | undefined
  return data?.error ?? null
}

export function errorMessage(e: unknown, fallback = 'Erro inesperado.'): string {
  const apiError = extractError(e)
  if (apiError) {
    if (apiError.fieldErrors && Object.keys(apiError.fieldErrors).length > 0) {
      return Object.entries(apiError.fieldErrors)
        .map(([field, msg]) => `${field}: ${msg}`)
        .join('; ')
    }
    if (apiError.message) return apiError.message
  }
  const err = e as AxiosLike
  return err?.message ?? fallback
}

export function errorCode(e: unknown): string | null {
  return extractError(e)?.code ?? null
}

export async function errorMessageFromBlob(e: unknown, fallback = 'Erro inesperado.'): Promise<string> {
  const err = e as { response?: { data?: unknown }; message?: string }
  const data = err?.response?.data
  if (data instanceof Blob) {
    try {
      const text = await data.text()
      const parsed = JSON.parse(text) as { error?: ApiErrorResponse['error']; message?: string }
      if (parsed?.error?.message) return parsed.error.message
      if (parsed?.message) return parsed.message
      return fallback
    } catch {
      return fallback
    }
  }
  return errorMessage(e, fallback)
}
