import type { FeatureModule } from '../common'
import { api } from '../common/api'

export const catalogFeature: FeatureModule = {
  id: 'catalog',
  title: '用例资产',
  description: '用 YAML 管理项目 Case、平台通用 Case 与平台托管脚本资源。',
}

export interface ExecutionRequirement { executorType: 'pytest-http' | 'playwright-web' | 'airtest'; interaction: 'HEADLESS' | 'UI'; capabilities: string[]; resourceProfile: string; leaseScope: 'CASE' | 'SUBFLOW'; sessionKey: string | null }
export interface TestCase { id: string; projectId: string; targetId: string; name: string; kind: 'ASSERTION' | 'FIXTURE'; scope: 'PROJECT' | 'SHARED'; tags: string[]; parameters: Record<string, unknown>; timeoutSeconds: number; scriptVersionId: string | null; executionRequirement: ExecutionRequirement; yamlManaged: boolean; definitionDigest: string | null }
export interface ScriptVersion { id: string; caseId: string; runner: 'pytest-http' | 'playwright-web' | 'airtest'; sourceRef: string; checksum: string; version: number }
export interface TestSuite { id: string; projectId: string; targetId: string; name: string; caseIds: string[]; tags: string[]; parameterBindings: Record<string, unknown>; version: number }
export interface CaseDefinitionView { caseId: string | null; projectId: string; valid: boolean; yaml: string; digest: string; name: string; kind: 'ASSERTION' | 'FIXTURE'; tags: string[]; parameters: Record<string, unknown>; timeoutSeconds: number; executionRequirement: ExecutionRequirement; assetId: string | null; sourceRef: string; entrypoint: string; checksum: string }
export interface CaseAsset { id: string; projectId: string; fileName: string; contentType: string; sizeBytes: number; sha256: string; uri: string; entrypoints: string[]; yamlSnippet: string; createdAt: string }

export const catalogApi = {
  cases: (projectId: string) => api<TestCase[]>(`/api/v1/projects/${projectId}/cases`),
  sharedCases: () => api<TestCase[]>('/api/v1/cases/shared'),
  validateDefinition: (projectId: string, definitionYaml: string) => api<CaseDefinitionView>(`/api/v1/projects/${projectId}/case-definitions/validate`, { method: 'POST', body: JSON.stringify({ yaml: definitionYaml }) }),
  createDefinition: (projectId: string, definitionYaml: string) => api<TestCase>(`/api/v1/projects/${projectId}/case-definitions`, { method: 'POST', body: JSON.stringify({ yaml: definitionYaml }) }),
  definition: (caseId: string) => api<CaseDefinitionView>(`/api/v1/cases/${caseId}/definition`),
  updateDefinition: (caseId: string, definitionYaml: string) => api<TestCase>(`/api/v1/cases/${caseId}/definition`, { method: 'PUT', body: JSON.stringify({ yaml: definitionYaml }) }),
  assets: (projectId: string) => api<CaseAsset[]>(`/api/v1/projects/${projectId}/assets`),
  uploadAsset: (projectId: string, file: File) => {
    const body = new FormData()
    body.append('file', file)
    return api<CaseAsset>(`/api/v1/projects/${projectId}/assets`, { method: 'POST', body })
  },
  asset: (assetId: string) => api<CaseAsset>(`/api/v1/assets/${assetId}`),
  legacyScripts: (caseId: string) => api<ScriptVersion[]>(`/api/v1/cases/${caseId}/scripts`),
  suites: (projectId: string, targetId: string) => api<TestSuite[]>(`/api/v1/projects/${projectId}/suites?targetId=${encodeURIComponent(targetId)}`),
  saveSuite: (projectId: string, value: Partial<TestSuite> & { targetId: string; name: string; caseIds: string[] }) => api<TestSuite>(
    value.id ? `/api/v1/suites/${value.id}` : `/api/v1/projects/${projectId}/suites`,
    { method: value.id ? 'PUT' : 'POST', body: JSON.stringify(value.id ? { expectedVersion: value.version, name: value.name, caseIds: value.caseIds, tags: value.tags, parameterBindings: value.parameterBindings } : value) },
  ),
}
