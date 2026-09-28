import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import WorkflowDesigner from './WorkflowDesigner.vue'

function response(data: unknown, status = 200) {
  const payload = status < 400 ? { data } : data
  return Promise.resolve(new Response(JSON.stringify(payload), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }))
}

const workflow = {
  id: 'w1',
  projectId: 'p1',
  targetId: 't1',
  name: '主流程',
  draftRevision: 0,
  latestVersion: 0,
  draft: { nodes: [], edges: [] },
}

const testCase = {
  id: 'c1',
  projectId: 'p1',
  name: 'C01 正常命中',
  kind: 'ASSERTION',
  scope: 'PROJECT',
  yamlManaged: true,
}

afterEach(() => vi.unstubAllGlobals())

describe('WorkflowDesigner', () => {
  it('saves the current DAG before publishing and binding it to the test job', async () => {
    const calls: Array<{ url: string; method: string; body?: Record<string, unknown> }> = []
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input), method = init?.method ?? 'GET'
      calls.push({ url, method, body: init?.body ? JSON.parse(String(init.body)) : undefined })
      if (url === '/api/v1/projects/p1/cases') return response([testCase])
      if (url === '/api/v1/cases/shared') return response([])
      if (url === '/api/v1/projects/p1/workflows') return response([workflow])
      if (url === '/api/v1/workflows/w1' && method === 'GET') return response(workflow)
      if (url === '/api/v1/workflows/w1/graph' && method === 'PUT') {
        const body = JSON.parse(String(init?.body))
        return response({ ...workflow, draftRevision: 1, draft: { nodes: body.nodes, edges: body.edges } })
      }
      if (url === '/api/v1/workflows/w1/publish' && method === 'POST') return response({ version: 1 })
      throw new Error(`unexpected request: ${method} ${url}`)
    }))

    const wrapper = mount(WorkflowDesigner, {
      props: { projectId: 'p1', workflowId: 'w1' },
      global: { stubs: { VueFlow: true, MiniMap: true, Controls: true } },
    })
    await flushPromises()

    const addCase = wrapper.findAll('button').find(item => item.text().includes('C01 正常命中'))!
    await addCase.trigger('click')
    const publish = wrapper.findAll('button').find(item => item.text().includes('发布并绑定任务'))!
    await publish.trigger('click')
    await flushPromises()

    const writes = calls.filter(call => call.method !== 'GET')
    expect(writes.map(call => `${call.method} ${call.url}`)).toEqual([
      'PUT /api/v1/workflows/w1/graph',
      'POST /api/v1/workflows/w1/publish',
    ])
    expect(writes[0].body).toMatchObject({ expectedVersion: 0 })
    expect((writes[0].body?.nodes as unknown[])).toHaveLength(1)
    expect(wrapper.emitted('published')).toEqual([[{ workflowId: 'w1', version: 1 }]])
  })

  it('does not publish when saving the current DAG fails', async () => {
    const calls: string[] = []
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input), method = init?.method ?? 'GET'
      calls.push(`${method} ${url}`)
      if (url === '/api/v1/projects/p1/cases') return response([testCase])
      if (url === '/api/v1/cases/shared') return response([])
      if (url === '/api/v1/projects/p1/workflows') return response([workflow])
      if (url === '/api/v1/workflows/w1' && method === 'GET') return response(workflow)
      if (url === '/api/v1/workflows/w1/graph' && method === 'PUT') {
        return response({ message: '草稿版本冲突' }, 409)
      }
      throw new Error(`unexpected request: ${method} ${url}`)
    }))

    const wrapper = mount(WorkflowDesigner, {
      props: { projectId: 'p1', workflowId: 'w1' },
      global: { stubs: { VueFlow: true, MiniMap: true, Controls: true } },
    })
    await flushPromises()

    await wrapper.findAll('button').find(item => item.text().includes('C01 正常命中'))!.trigger('click')
    await wrapper.findAll('button').find(item => item.text().includes('发布并绑定任务'))!.trigger('click')
    await flushPromises()

    expect(calls).toContain('PUT /api/v1/workflows/w1/graph')
    expect(calls).not.toContain('POST /api/v1/workflows/w1/publish')
    expect(wrapper.get('[role="alert"]').text()).toContain('草稿版本冲突')
  })
})
