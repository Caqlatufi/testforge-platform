export interface ApiEnvelope<T> { data: T }

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message)
  }
}

export async function api<T>(path: string, init?: RequestInit): Promise<T> {
  const headers = new Headers(init?.headers)
  if (!(init?.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(path, {
    ...init,
    headers,
  })
  const payload = (await response.json().catch(() => ({}))) as Partial<ApiEnvelope<T>> & {
    message?: string
    detail?: string
  }
  if (!response.ok) throw new ApiError(payload.message ?? payload.detail ?? `HTTP ${response.status}`, response.status)
  if (!('data' in payload)) throw new ApiError('平台响应缺少 data', response.status)
  return payload.data as T
}
