export interface ApiError {
  code: string
  message: string
  fieldErrors?: Record<string, string>
}

export interface ApiErrorResponse {
  success: false
  error: ApiError
  path: string
  timestamp: string
  traceId?: string
}
