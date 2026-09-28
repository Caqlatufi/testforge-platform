import type { FeatureModule } from '../common'
import { api, ApiError } from '../common/api'

export const reportsFeature: FeatureModule = {
  id: 'reports',
  title: '质量报告',
  description: '呈现确定性结果、耗时、分类与诊断证据。',
}

export interface CaseHistoryItem {
  recordedAt: string
  runId: string
  taskId: string
  attemptId: string
  finalStatus: 'PASSED' | 'ASSERTION_FAILED' | 'INFRA_FAILED' | 'CANCELLED'
  durationMs: number
  failureType: string | null
}

export interface CaseHistory {
  caseId: string
  sampleSize: number
  sufficientData: boolean
  flaky: boolean
  statusTransitions: number
  latestFailureType: string | null
  recentResults: CaseHistoryItem[]
}

export function getCaseHistory(caseId: string): Promise<CaseHistory> {
  return api<CaseHistory>(`/api/v1/reports/runs/cases/${caseId}/history`)
}

export interface AiEvidenceCitation { evidenceId: string; reason: string }
export interface AiDiagnosis {
  diagnosisId: string
  reportId: string
  category: string
  confidence: number
  evidence: AiEvidenceCitation[]
  suggestions: string[]
  missingEvidence: string[]
  provider: string
  model: string
  reasoningEffort: string
  reused: boolean
  generatedAt: string
}

export interface AiDiagnosisStatus { available: boolean; status: string; message: string; provider: string; model: string; reasoningEffort: string }

export function getAiDiagnosisStatus(reportId: string): Promise<AiDiagnosisStatus> {
  return api<AiDiagnosisStatus>(`/api/v1/reports/${reportId}/diagnosis/status`)
}

export async function getLatestAiDiagnosis(reportId: string): Promise<AiDiagnosis | null> {
  try { return await api<AiDiagnosis>(`/api/v1/reports/${reportId}/diagnosis`) }
  catch (error) { if (error instanceof ApiError && error.status === 404) return null; throw error }
}

export function diagnoseReport(reportId: string, forceRefresh: boolean): Promise<AiDiagnosis> {
  return api<AiDiagnosis>(`/api/v1/reports/${reportId}/diagnosis`, {
    method: 'POST',
    body: JSON.stringify({ requestKey: crypto.randomUUID(), forceRefresh }),
  })
}
