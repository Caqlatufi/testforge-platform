import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RunReportDetail from './RunReportDetail.vue'

function response(data: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(status < 400 ? { data } : { message: data }), { status, headers: { 'Content-Type': 'application/json' } }))
}

class FakeEventSource {
  static instances: FakeEventSource[] = []
  onopen: ((event: Event) => void) | null = null
  onerror: ((event: Event) => void) | null = null
  close = vi.fn()
  constructor(readonly url: string) { FakeEventSource.instances.push(this) }
  addEventListener() {}
}

afterEach(() => { vi.unstubAllGlobals(); FakeEventSource.instances = [] })

describe('RunReportDetail', () => {
  it('loads only the selected job run, graph and report and closes its SSE on back', async () => {
    const run = { id: 'run-1', testJobId: 'job-1', jobConfigVersion: 1, state: 'RUNNING', version: 1, workflowId: 'wf-1', workflowVersion: 3, processConcurrency: 1, deviceConcurrency: 1, createdAt: '2026-09-26T08:00:00Z', taskCounts: { RUNNING: 1 }, tasks: [] }
    vi.stubGlobal('EventSource', FakeEventSource)
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/test-jobs/job-1/attempts') return response([run])
      if (url === '/api/v1/runs/run-1') return response(run)
      if (url === '/api/v1/runs/run-1/graph') return response({ runId: 'run-1', testJobId: 'job-1', workflowId: 'wf-1', workflowVersion: 3, compiledFallback: false, nodes: [{ id: 'n1', type: 'CASE', label: '登录验证', positionX: 0, positionY: 0, state: 'RUNNING', taskIds: [], totalTasks: 1, completedTasks: 0 }], edges: [] })
      if (url === '/api/v1/reports/runs/run-1') return response({ runId: 'run-1', total: 1, passed: 1, failed: 0, p95DurationMs: 120, results: [{ taskId: 'task-1', attemptId: 'attempt-1', status: 'PASSED', durationMs: 120, summary: 'ok', artifactKeys: ['runs/run-1/final.png'] }], artifactKeys: ['runs/run-1/final.png'] })
      if (url === '/api/v1/reports/run-1/diagnosis') return response('not found', 404)
      if (url === '/api/v1/reports/run-1/diagnosis/status') return response({ available: false, status: 'DISABLED', message: 'AI Provider 未配置', provider: 'none', model: '', reasoningEffort: '' })
      throw new Error(`unexpected URL: ${url}`)
    }))

    const wrapper = mount(RunReportDetail, {
      props: { job: { taskId: 'job-1', projectId: 'p1', projectName: 'Skill Sandbox', name: '回归测试', code: 'regression', taskState: 'ACTIVE', attemptCount: 1, activeAttemptCount: 1, currentAttemptId: 'run-1', currentAttemptState: 'RUNNING', progress: 50, resolvedCommit: '1234567890abcdef', commitMessage: 'fix: target', workflowVersion: 3 } },
      global: { stubs: { VueFlow: { props: ['nodes'], template: '<div data-test="graph">{{ nodes.map((node) => node.data.label).join("|") }}</div>' }, Controls: true } },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('回归测试')
    expect(wrapper.text()).toContain('执行流程')
    expect(wrapper.find('[data-test="graph"]').text()).toContain('登录验证')
    expect(wrapper.text()).toContain('通过率')
    expect(wrapper.text()).toContain('100%')
    expect(wrapper.text()).toContain('SCREENSHOT · final.png')
    const aiButton = wrapper.findAll('button').find(button => button.text().includes('AI 辅助诊断'))
    expect(aiButton?.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('AI Provider 未配置')
    expect(FakeEventSource.instances[0].url).toBe('/api/v1/runs/run-1/events')

    await wrapper.find('.run-report-header button').trigger('click')
    expect(FakeEventSource.instances[0].close).toHaveBeenCalled()
    expect(wrapper.emitted('back')).toHaveLength(1)
  })
})
