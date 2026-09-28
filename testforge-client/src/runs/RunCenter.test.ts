import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RunCenter from './RunCenter.vue'

function response(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ data }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
}

afterEach(() => vi.unstubAllGlobals())

describe('RunCenter', () => {
  it('shows resource queues before one-row-per-job report entries and opens a separate detail', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/v1/runs/run-2/cancel' && init?.method === 'POST') return response(null)
      if (url === '/api/v1/test-jobs/execution-summaries') return response([
        { taskId: 'job-1', projectId: 'p1', projectName: 'Skill Sandbox', name: '回归测试', code: 'regression', taskState: 'ACTIVE', attemptCount: 2, activeAttemptCount: 1, currentAttemptId: 'run-2', currentAttemptState: 'QUEUED', currentAttemptPhase: 'WAITING_DEPLOYMENT', progress: 0, lastExecutedAt: '2026-09-26T08:00:00Z', resolvedCommit: '1234567890abcdef', commitMessage: 'fix: target', workflowVersion: 3 },
        { taskId: 'job-2', projectId: 'p1', projectName: 'Skill Sandbox', name: '尚未执行', code: 'new-job', taskState: 'ACTIVE', attemptCount: 0, activeAttemptCount: 0, progress: 0, workflowVersion: 1 },
      ])
      if (url === '/api/v1/workers') return response([{ workerId: 'agent-1', capabilities: ['HEADLESS'], maxConcurrency: 4, status: 'ONLINE' }])
      if (url === '/api/v1/device-slots') return response([])
      if (url === '/api/v1/monitoring/resources') return response({ deploymentWaiting: 2, processQueued: 1, processRunning: 2, processCapacity: 4, deviceQueued: 0, deviceRunning: 1, deviceCapacity: 2, deviceAvailable: 1, onlineWorkers: 1, executorCapacity: { 'pytest-http': 4 } })
      throw new Error(`unexpected URL: ${url}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(RunCenter, { global: { stubs: { RunReportDetail: { props: ['job'], template: '<div data-test="report-detail">{{ job.name }}</div>' } } } })
    await flushPromises()

    const resource = wrapper.find('.operations-resources')
    const jobs = wrapper.find('.operations-jobs')
    expect(resource.exists()).toBe(true)
    expect(jobs.exists()).toBe(true)
    expect(resource.element.compareDocumentPosition(jobs.element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(wrapper.findAll('.job-run-row')).toHaveLength(2)
    expect(wrapper.text()).toContain('等待发布 Case')
    expect(wrapper.text()).toContain('待调度 Case1')
    expect(wrapper.text()).toContain('执行中 Case3')
    expect(wrapper.text()).toContain('脚本执行槽2/4')
    expect(wrapper.text()).toContain('pytest-http 4')
    expect(wrapper.text()).toContain('UI 测试机1/2')
    expect(wrapper.findAll('.resource-stats > div')).toHaveLength(5)
    expect(wrapper.text()).toContain('1234567890ab')
    expect(wrapper.text()).not.toContain('regression')

    const cancelButton = wrapper.findAll('.job-run-action button').find(button => button.text() === '取消执行')
    expect(cancelButton).toBeDefined()
    await cancelButton!.trigger('click')
    await flushPromises()
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/runs/run-2/cancel', expect.objectContaining({ method: 'POST' }))

    const reportButtons = wrapper.findAll('.job-run-action button').filter(button => button.text() === '查看报告')
    expect(reportButtons[0].attributes('disabled')).toBeUndefined()
    expect(reportButtons[1].attributes('disabled')).toBeDefined()
    await reportButtons[0].trigger('click')
    expect(wrapper.find('[data-test="report-detail"]').text()).toBe('回归测试')
    expect(wrapper.find('.operations-resources').exists()).toBe(false)
  })
})
