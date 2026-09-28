import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AssetManagement from './AssetManagement.vue'

function response(data: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }))
}

afterEach(() => window.history.replaceState({}, '', '/'))

describe('AssetManagement', () => {
  it('shows the real empty state after loading', async () => {
    vi.stubGlobal('fetch', vi.fn(() => response({ data: [] })))
    const wrapper = mount(AssetManagement)

    await flushPromises()

    expect(wrapper.text()).toContain('暂无被测项目。')
  })

  it('renders a structured backend error in the page', async () => {
    window.history.replaceState({}, '', '/?project=missing-project')
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response({ data: [{ id: 'missing-project', name: 'Missing', code: 'missing', state: 'ACTIVE', targets: [] }] })
      return response({ code: 'RESOURCE_NOT_FOUND', message: '项目不存在' }, 404)
    }))
    const wrapper = mount(AssetManagement)

    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('项目不存在')
  })

  it('renders project, Jenkins pipeline and scoped cases without the retired suite authoring UI', async () => {
    const project = {
      id: 'p1', name: 'Demo Project', code: 'demo-project', state: 'ACTIVE',
      targetType: 'HTTP_SERVICE', repositoryUrl: 'https://git.example/demo.git', defaultBranch: 'main',
      ciProvider: 'JENKINS', ciServerUrl: 'http://jenkins.local', ciFolder: 'demo', ciCredentialRef: 'jenkins-local',
      targets: [{ id: 't1', projectId: 'p1', name: 'HTTP Target', type: 'HTTP_SERVICE', environments: [] }],
    }
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response({ data: [{ ...project, targets: [] }] })
      if (url === '/api/v1/projects/p1') return response({ data: project })
      if (url === '/api/v1/projects/p1/pipelines') return response({ data: [{ externalId: 'pipeline-1', name: 'Deploy Demo', provider: 'JENKINS', serverUrl: 'http://jenkins.local', jobName: 'demo/deploy', revision: '3', enabled: true, kind: 'MULTIBRANCH' }] })
      if (url === '/api/v1/projects/p1/cases') return response({ data: [{ id: 'c1', projectId: 'p1', targetId: null, name: 'Health Case', kind: 'ASSERTION', scope: 'PROJECT', tags: [], parameters: {}, timeoutSeconds: 30, scriptVersionId: null, yamlManaged: true, definitionDigest: 'sha256:abc', executionRequirement: { executorType: 'pytest-http', interaction: 'HEADLESS', capabilities: [], resourceProfile: 'script-small', leaseScope: 'CASE', sessionKey: null } }] })
      if (url === '/api/v1/projects/p1/assets') return response({ data: [
        { id: 'a-current', projectId: 'p1', fileName: 'health.py', contentType: 'text/x-python', sizeBytes: 128, sha256: `sha256:${'a'.repeat(64)}`, uri: 'testforge://assets/a-current', entrypoints: ['health.py'], yamlSnippet: '', createdAt: '2026-09-25T00:00:00Z' },
        { id: 'a-history', projectId: 'p1', fileName: 'old-health.py', contentType: 'text/x-python', sizeBytes: 128, sha256: `sha256:${'b'.repeat(64)}`, uri: 'testforge://assets/a-history', entrypoints: ['old-health.py'], yamlSnippet: '', createdAt: '2026-09-24T00:00:00Z' },
      ] })
      if (url === '/api/v1/cases/c1/definition') return response({ data: { caseId: 'c1', projectId: 'p1', valid: true, yaml: 'apiVersion: testforge.io/v1alpha1', digest: 'sha256:abc', name: 'Health Case', assetId: 'a-current', executionRequirement: { executorType: 'pytest-http' } } })
      throw new Error(`unexpected URL: ${url}`)
    }))
    const wrapper = mount(AssetManagement)

    await flushPromises()

    expect(wrapper.text()).toContain('Demo Project')
    expect(wrapper.text()).not.toContain('demo-project')
    const labels = wrapper.findAll('label').map(label => label.text())
    expect(labels.some(label => label.startsWith('编码'))).toBe(false)
    expect(labels.some(label => label.startsWith('应用类型'))).toBe(false)
    expect(labels.some(label => label.startsWith('CI Provider'))).toBe(false)
    expect(labels.some(label => label.startsWith('Jenkins 地址'))).toBe(false)
    expect(labels.some(label => label.startsWith('Folder'))).toBe(false)
    expect(labels.some(label => label.startsWith('凭据引用'))).toBe(false)
    expect(labels.some(label => label.startsWith('版本类型'))).toBe(false)
    expect(wrapper.text()).toContain('Deploy Demo · MULTIBRANCH · READY')
    expect(wrapper.findAll('button').some(button => button.text().includes('Deploy Demo'))).toBe(false)
    expect(wrapper.text()).not.toContain('新建环境')
    expect(wrapper.text()).not.toContain('Jenkins 发布键')
    expect(wrapper.text()).toContain('Health Case')
    expect(wrapper.text()).toContain('pytest-http')
    expect(wrapper.text()).toContain('YAML-FIRST TEST ASSETS')
    expect(wrapper.text()).toContain('health.py')
    expect(wrapper.text()).not.toContain('old-health.py')
    expect(wrapper.text()).not.toContain('作用域')
    expect(wrapper.text()).not.toContain('执行器')
    expect(wrapper.text()).not.toContain('资源规格')
    expect(wrapper.text()).not.toContain('租约范围')
    expect(wrapper.text()).not.toContain('能力要求')
    expect(wrapper.text()).not.toContain('新建 Suite')
  })

  it('keeps existing cases visible when the optional asset endpoint is not deployed yet', async () => {
    const project = {
      id: 'p1', name: 'Demo Project', code: 'demo-project', state: 'ACTIVE',
      targetType: 'HTTP_SERVICE', repositoryUrl: 'https://git.example/demo.git', defaultBranch: 'main',
      targets: [{ id: 't1', projectId: 'p1', name: 'HTTP Target', type: 'HTTP_SERVICE', environments: [] }],
    }
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response({ data: [{ ...project, targets: [] }] })
      if (url === '/api/v1/projects/p1') return response({ data: project })
      if (url === '/api/v1/projects/p1/pipelines') return response({ data: [] })
      if (url === '/api/v1/projects/p1/cases') return response({ data: [{ id: 'legacy-1', projectId: 'p1', targetId: 't1', name: 'Legacy Case', kind: 'ASSERTION', scope: 'PROJECT', tags: [], parameters: {}, timeoutSeconds: 30, scriptVersionId: 's1', yamlManaged: false, definitionDigest: null, executionRequirement: { executorType: 'pytest-http', interaction: 'HEADLESS', capabilities: [], resourceProfile: 'script-small', leaseScope: 'CASE', sessionKey: null } }] })
      if (url === '/api/v1/projects/p1/assets') return response({ message: 'HTTP 404' }, 404)
      throw new Error(`unexpected URL: ${url}`)
    }))

    const wrapper = mount(AssetManagement)
    await flushPromises()

    expect(wrapper.text()).toContain('Legacy Case')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })

  it('uploads and binds a dedicated Airtest asset to the current legacy Case', async () => {
    const project = {
      id: 'p1', name: 'Demo Project', code: 'demo-project', state: 'ACTIVE',
      targetType: 'DESKTOP', repositoryUrl: 'https://git.example/demo.git', defaultBranch: 'main',
      targets: [{ id: 't1', projectId: 'p1', name: 'Desktop Target', type: 'DESKTOP', environments: [] }],
    }
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response({ data: [{ ...project, targets: [] }] })
      if (url === '/api/v1/projects/p1') return response({ data: project })
      if (url === '/api/v1/projects/p1/pipelines') return response({ data: [] })
      if (url === '/api/v1/projects/p1/assets' && init?.method === 'POST') return response({ data: { id: 'a2', projectId: 'p1', fileName: 'c02-aim-deviation.air.zip', contentType: 'application/zip', sizeBytes: 1024, sha256: `sha256:${'a'.repeat(64)}`, uri: 'testforge://assets/a2', entrypoints: ['c02-aim-deviation.air'], yamlSnippet: '', createdAt: '2026-09-25T00:00:00Z' } })
      if (url === '/api/v1/projects/p1/assets') return response({ data: [] })
      if (url === '/api/v1/projects/p1/cases') return response({ data: [{
        id: 'legacy-airtest', projectId: 'p1', targetId: 't1', name: 'C02 方向偏离',
        kind: 'ASSERTION', scope: 'PROJECT', tags: ['airtest'],
        parameters: { scenarioId: 'core-aim-deviation-v1', description: '瞄准线偏离目标' },
        timeoutSeconds: 90, scriptVersionId: 's4', yamlManaged: false, definitionDigest: null,
        executionRequirement: { executorType: 'airtest', interaction: 'UI', capabilities: [], resourceProfile: 'ui-default', leaseScope: 'CASE', sessionKey: null },
      }] })
      throw new Error(`unexpected URL: ${url}`)
    }))

    const wrapper = mount(AssetManagement)
    await flushPromises()

    const yaml = (wrapper.get('.case-yaml-editor').element as HTMLTextAreaElement).value
    expect(yaml).toContain('executor: airtest')
    expect(yaml).not.toContain('scenarioId:')
    expect(yaml).toContain('asset: testforge://assets/REPLACE_WITH_UPLOADED_ASSET_ID')
    expect(yaml).not.toContain('executor: pytest-http')

    const input = wrapper.get('input[type="file"]')
    Object.defineProperty(input.element, 'files', { configurable: true, value: [new File(['zip'], 'c02-aim-deviation.air.zip', { type: 'application/zip' })] })
    await input.trigger('change')
    await flushPromises()
    const inserted = (wrapper.get('.case-yaml-editor').element as HTMLTextAreaElement).value
    expect(inserted).toContain('entrypoint: "c02-aim-deviation.air"')
    expect(inserted).not.toContain('entrypoint: "c02-aim-deviation.air.zip"')
    expect(wrapper.text()).toContain('当前 Case 脚本')
    expect(wrapper.text()).toContain('c02-aim-deviation.air.zip')
  })

  it('creates a YAML-first project case without exposing targetId or ScriptVersion', async () => {
    const project = {
      id: 'p1', name: 'Demo Project', code: 'demo-project', state: 'ACTIVE',
      targetType: 'DESKTOP', repositoryUrl: 'https://git.example/demo.git', defaultBranch: 'main',
      targets: [{ id: 't1', projectId: 'p1', name: 'Demo Project', type: 'DESKTOP', environments: [] }],
    }
    let submitted: Record<string, unknown> | undefined
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input)
      if (url === '/api/v1/projects') return response({ data: [{ ...project, targets: [] }] })
      if (url === '/api/v1/projects/p1' && !init?.method) return response({ data: project })
      if (url === '/api/v1/projects/p1/pipelines') return response({ data: [] })
      if (url === '/api/v1/projects/p1/case-definitions' && init?.method === 'POST') {
        submitted = JSON.parse(String(init.body)) as Record<string, unknown>
        return response({ data: { id: 'c2', projectId: 'p1', targetId: null, name: 'Windows smoke', kind: 'ASSERTION', scope: 'PROJECT', tags: [], parameters: {}, timeoutSeconds: 30, scriptVersionId: null, yamlManaged: true, definitionDigest: 'sha256:def', executionRequirement: { executorType: 'pytest-http', interaction: 'HEADLESS', capabilities: [], resourceProfile: 'script-small', leaseScope: 'CASE', sessionKey: null } } }, 201)
      }
      if (url === '/api/v1/projects/p1/cases') return response({ data: [] })
      if (url === '/api/v1/projects/p1/assets') return response({ data: [] })
      if (url === '/api/v1/cases/c2/definition') return response({ data: { caseId: 'c2', projectId: 'p1', valid: true, yaml: String(submitted?.yaml), digest: 'sha256:def', name: 'Windows smoke', assetId: 'a-materialized' } })
      if (url === '/api/v1/assets/a-materialized') return response({ data: { id: 'a-materialized', projectId: 'p1', fileName: 'case_test.py', contentType: 'text/x-python', sizeBytes: 42, sha256: `sha256:${'c'.repeat(64)}`, uri: 'testforge://assets/a-materialized', entrypoints: ['case_test.py'], yamlSnippet: '', createdAt: '2026-09-26T00:00:00Z' } })
      throw new Error(`unexpected URL: ${url}`)
    }))
    const wrapper = mount(AssetManagement)
    await flushPromises()

    const editor = wrapper.get('.case-yaml-editor')
    const yaml = (editor.element as HTMLTextAreaElement).value
    await editor.setValue(yaml.replace('新建 Case', 'Windows smoke'))
    const create = wrapper.findAll('button').find(button => button.text().includes('创建 Case'))
    await create!.trigger('click')
    await flushPromises()

    expect(String(submitted?.yaml)).toContain('name: "Windows smoke"')
    expect(String(submitted?.yaml)).toContain('content: |')
    expect(submitted).not.toHaveProperty('targetId')
    expect(wrapper.text()).toContain('Case YAML 已创建')
    expect(wrapper.text()).toContain('case_test.py')
  })
})
