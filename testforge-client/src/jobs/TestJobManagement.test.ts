import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import TestJobManagement from './TestJobManagement.vue'

function response(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }))
}

afterEach(() => vi.unstubAllGlobals())

describe('TestJobManagement', () => {
  it('renders the newest test job first even when the API response is unordered', async () => {
    const base = { projectId: 'p1', code: 'code', description: '', workflowId: 'w1', workflowVersion: 2, workflowChecksum: 'sha256:x', revisionType: 'DEFAULT_BRANCH', revisionValue: null, pipelineExternalId: 'pipe1', platform: 'WINDOWS', priority: 5, processConcurrency: 100, deviceConcurrency: 100, state: 'DRAFT', configVersion: 1, version: 0, updatedAt: '2026-09-24T00:00:00Z' }
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response([{ id: 'p1', name: 'Skill Sandbox', code: 'skill-sandbox', state: 'ACTIVE', targetType: 'DESKTOP', targets: [] }])
      if (url === '/api/v1/test-jobs') return response([
        { ...base, id: 'older', name: '较早任务', createdAt: '2026-09-24T00:00:00Z' },
        { ...base, id: 'newer', name: '最新任务', createdAt: '2026-09-25T00:00:00Z' },
      ])
      if (url === '/api/v1/projects/p1/workflows') return response([{ id: 'w1', projectId: 'p1', name: 'Desktop smoke', latestVersion: 2 }])
      if (url === '/api/v1/projects/p1/revisions?type=BRANCH') return response([{ type: 'BRANCH', name: 'main', commitSha: 'a'.repeat(40), commitMessage: 'latest change', committedAt: '2026-09-24T00:00:00Z', defaultBranch: true }])
      throw new Error(`unexpected URL: ${url}`)
    }))

    const wrapper = mount(TestJobManagement)
    await flushPromises()

    expect(wrapper.findAll('.run-row').map(row => row.find('strong').text())).toEqual(['最新任务', '较早任务'])
  })

  it('only asks for business intent and keeps scheduling parameters system-managed', async () => {
    let savedBody: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response([{ id: 'p1', name: 'Skill Sandbox', code: 'skill-sandbox', state: 'ACTIVE', targetType: 'DESKTOP', targets: [] }])
      if (url === '/api/v1/test-jobs' && init?.method === 'POST') {
        savedBody = JSON.parse(String(init.body))
        return response({ id: 'j1', projectId: 'p1', code: '00000000-0000-0000-0000-000000000001', name: '主流程回归', description: '', workflowId: 'w1', workflowVersion: 2, workflowChecksum: 'sha256:x', revisionType: 'DEFAULT_BRANCH', revisionValue: null, pipelineExternalId: 'pipe1', platform: 'WINDOWS', priority: 5, processConcurrency: 100, deviceConcurrency: 100, state: 'DRAFT', configVersion: 1, version: 0, createdAt: '2026-09-24T00:00:00Z', updatedAt: '2026-09-24T00:00:00Z' })
      }
      if (url === '/api/v1/test-jobs') return response([])
      if (url === '/api/v1/projects/p1/workflows') return response([{ id: 'w1', projectId: 'p1', name: 'Desktop smoke', latestVersion: 2 }])
      if (url === '/api/v1/projects/p1/revisions?type=BRANCH') return response([{ type: 'BRANCH', name: 'main', commitSha: 'a'.repeat(40), commitMessage: 'latest change', committedAt: '2026-09-24T00:00:00Z', defaultBranch: true }])
      if (url === '/api/v1/projects/p1/cases' || url === '/api/v1/cases/shared') return response([])
      throw new Error(`unexpected URL: ${url}`)
    }))
    const wrapper = mount(TestJobManagement)
    await flushPromises()

    expect(wrapper.text()).toContain('Skill Sandbox')
    expect(wrapper.text()).toContain('Desktop smoke · v2')
    expect(wrapper.text()).toContain('默认使用项目最近发布的工作流')
    expect(wrapper.text()).toContain('latest change')
    expect(wrapper.text()).toContain('aaaaaaaaaa')
    expect(wrapper.text()).toContain('目标系统')
    expect(wrapper.text()).toContain('Windows')
    expect(wrapper.text()).toContain('普通')
    expect(wrapper.text()).toContain('紧急')
    expect(wrapper.text()).toContain('任务定义')
    expect(wrapper.text()).toContain('测试内容')
    expect(wrapper.text()).toContain('执行配置')
    expect(wrapper.text()).not.toContain('任务编码')
    expect(wrapper.text()).not.toContain('固定版本')
    expect(wrapper.text()).not.toContain('Pipeline')
    expect(wrapper.text()).not.toContain('进程池并发')
    expect(wrapper.text()).not.toContain('设备池并发')
    expect(wrapper.text()).not.toContain('测试环境')
    expect(wrapper.text()).not.toContain('资源池')

    const workflow = wrapper.findAll('select').find(item => item.text().includes('Desktop smoke'))!
    expect(workflow.element.value).toBe('w1')
    const name = wrapper.findAll('label').find(item => item.text().includes('任务名称'))!
    await name.find('input').setValue('主流程回归')
    await wrapper.find('form.form-grid').trigger('submit')
    await flushPromises()

    expect(savedBody).toMatchObject({ projectId: 'p1', name: '主流程回归', workflowId: 'w1', revisionType: 'BRANCH', revisionValue: 'main', platform: 'WINDOWS', priority: 5 })
    expect(savedBody).not.toHaveProperty('code')
    expect(savedBody).not.toHaveProperty('workflowVersion')
    expect(savedBody).not.toHaveProperty('pipelineExternalId')
    expect(savedBody).not.toHaveProperty('processConcurrency')
    expect(savedBody).not.toHaveProperty('deviceConcurrency')
  })

  it('opens workflow editing as an exclusive secondary workspace and returns to the task', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response([{ id: 'p1', name: 'Skill Sandbox', code: 'skill-sandbox', state: 'ACTIVE', targetType: 'DESKTOP', targets: [] }])
      if (url === '/api/v1/test-jobs') return response([])
      if (url === '/api/v1/projects/p1/workflows') return response([])
      if (url === '/api/v1/projects/p1/revisions?type=BRANCH') return response([{ type: 'BRANCH', name: 'main', commitSha: 'a'.repeat(40), commitMessage: 'latest change', committedAt: '2026-09-24T00:00:00Z', defaultBranch: true }])
      if (url === '/api/v1/projects/p1/cases' || url === '/api/v1/cases/shared') return response([])
      throw new Error(`unexpected URL: ${url}`)
    }))
    const wrapper = mount(TestJobManagement)
    await flushPromises()

    const open = wrapper.findAll('button').find(item => item.text().includes('新建 Workflow'))!
    await open.trigger('click')
    await flushPromises()

    expect(wrapper.find('.workflow-page').exists()).toBe(true)
    expect(wrapper.find('.asset-layout').exists()).toBe(false)
    expect(wrapper.find('.workflow-create-state').exists()).toBe(true)
    expect(wrapper.emitted('workspace-change')).toContainEqual([true])

    await wrapper.find('.workflow-back').trigger('click')
    expect(wrapper.find('.workflow-page').exists()).toBe(false)
    expect(wrapper.find('.asset-layout').exists()).toBe(true)
    expect(wrapper.emitted('workspace-change')).toContainEqual([false])
  })
})
