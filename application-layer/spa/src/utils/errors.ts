/**
 * Extrai a mensagem de erro de uma exception (típico axios error).
 * Uso: `catch (e: unknown) { setError(errorMessage(e, 'fallback')) }`
 */
export function errorMessage(e: unknown, fallback = 'Erro inesperado.'): string {
  const err = e as { response?: { data?: { message?: string } }; message?: string }
  return err?.response?.data?.message ?? err?.message ?? fallback
}
