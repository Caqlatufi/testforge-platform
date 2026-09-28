import { afterEach, describe, expect, it, vi } from 'vitest'
import { diagnoseReport, getLatestAiDiagnosis } from './index'

afterEach(() => vi.unstubAllGlobals())

describe('AI diagnosis API', () => {
  it('treats missing latest diagnosis as an empty optional result', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ code: 'DIAGNOSIS_NOT_FOUND', message: 'not found' }),
      { status: 404, headers: { 'Content-Type': 'application/json' } },
    )))

    await expect(getLatestAiDiagnosis('report-1')).resolves.toBeNull()
  })

  it('uses a fresh request key and forwards force refresh', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ data: {
      diagnosisId: 'diagnosis-1', reportId: 'report-1', category: 'UNKNOWN', confidence: 0,
      evidence: [], suggestions: [], missingEvidence: [], provider: 'codex-cli',
      model: 'gpt-5.6-luna', reasoningEffort: 'high', reused: false,
      generatedAt: '2026-09-20T00:00:00Z',
    } }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
    vi.stubGlobal('fetch', fetchMock)

    await diagnoseReport('report-1', true)

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    const body = JSON.parse(String(init.body)) as { requestKey: string; forceRefresh: boolean }
    expect(body.requestKey).toMatch(/^[0-9a-f-]{36}$/)
    expect(body.forceRefresh).toBe(true)
  })
})
