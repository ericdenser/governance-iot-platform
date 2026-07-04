/**
 * Extrai a mensagem de erro de uma exception (típico axios error).
 * Uso: `catch (e: unknown) { setError(errorMessage(e, 'fallback')) }`
 */
export function errorMessage(e: unknown, fallback = 'Erro inesperado.'): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string }
  return err?.response?.data?.message ?? err?.message ?? fallback
}

// Endpoints com responseType: 'blob' recebem o erro como Blob em vez de JSON.
// Este helper faz o unwrap async antes de delegar pro errorMessage padrão.
export async function errorMessageFromBlob(e: unknown, fallback = 'Erro inesperado.'): Promise<string> {
  const err = e as { response?: { data?: unknown }; message?: string }
  const data = err?.response?.data
  if (data instanceof Blob) {
    try {
      const text = await data.text()
      const parsed = JSON.parse(text)
      return parsed?.message ?? fallback
    } catch {
      return fallback
    }
  }
  return errorMessage(e, fallback)
}
