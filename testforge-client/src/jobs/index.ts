import { api } from '../common/api'
import type { FeatureModule } from '../common'
import type { PipelineRef, RevisionType } from '../projects'
import type { EnvironmentPlatform } from '../environments'

export type TestJobState = 'DRAFT' | 'ACTIVE' | 'DISABLED'
export const jobsFeature: FeatureModule = { id: 'jobs', title: '测试任务', description: '配置可重复执行的 Test Job。' }
export interface TestJob { id: string; projectId: string; code: string; name: string; description?: string; workflowId: string; workflowVersion: number; workflowChecksum: string; revisionType: RevisionType; revisionValue?: string; resolvedCommit?: string; commitMessage?: string; revisionResolvedAt?: string; pipelineExternalId: string; pipelineName?: string; pipelineRevision?: string; environmentExternalId?: string | null; environmentName?: string; platform: EnvironmentPlatform; priority: number; processConcurrency: number; deviceConcurrency: number; state: TestJobState; configVersion: number; version: number; createdAt: string; updatedAt: string }
export interface TestJobSave { projectId: string; name: string; description: string; workflowId: string; revisionType: RevisionType; revisionValue: string; platform: EnvironmentPlatform; priority: 5 | 9; configVersion: number }

export const testJobApi = {
  list: (projectId?: string) => api<TestJob[]>(`/api/v1/test-jobs${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`)
    .then(items => [...items].sort((left, right) => Date.parse(right.createdAt) - Date.parse(left.createdAt))),
  save: (value: TestJobSave & { id?: string }) => api<TestJob>(value.id ? `/api/v1/test-jobs/${value.id}` : '/api/v1/test-jobs', { method: value.id ? 'PUT' : 'POST', body: JSON.stringify(value) }),
  activate: (value: TestJob) => api<TestJob>(`/api/v1/test-jobs/${value.id}/activate`, { method: 'POST', body: JSON.stringify({ configVersion: value.configVersion }) }),
  disable: (value: TestJob) => api<TestJob>(`/api/v1/test-jobs/${value.id}/disable`, { method: 'POST', body: JSON.stringify({ configVersion: value.configVersion }) }),
  copy: (id: string) => api<TestJob>(`/api/v1/test-jobs/${id}/copies`, { method: 'POST' }),
  execute: (id: string) => api<unknown>(`/api/v1/test-jobs/${id}/execute`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID() }) }),
  launch: (id: string) => api<unknown>(`/api/v1/test-jobs/${id}/execute`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID() }) }),
  comparison: (id: string, baseline: { type: RevisionType; value: string }, candidate: { type: RevisionType; value: string }) => api<unknown>(`/api/v1/test-jobs/${id}/comparison-runs`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID(), baselineRevision: { type: baseline.type, value: baseline.type === 'DEFAULT_BRANCH' ? null : baseline.value }, candidateRevision: { type: candidate.type, value: candidate.type === 'DEFAULT_BRANCH' ? null : candidate.value } }) }),
}

export type { PipelineRef }
