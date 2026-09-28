import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import EnvironmentManagement from './EnvironmentManagement.vue'

function response(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ data }), {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  }))
}

afterEach(() => vi.unstubAllGlobals())

describe('EnvironmentManagement', () => {
  it('shows shared multi-platform environments outside Project management', async () => {
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/v1/environments') {
        return response([
          { id: 'win-1', name: 'Windows VM 01', platform: 'WINDOWS', resourcePoolKey: 'windows', providerEnvironmentKey: 'windows-vm-01', capacity: 1, enabled: true, initializeOnNextDeploy: false, config: {}, secretRefs: {} },
          { id: 'android-1', name: 'Android Emulator 01', platform: 'ANDROID', resourcePoolKey: 'android', providerEnvironmentKey: 'android-emulator-01', capacity: 1, enabled: true, initializeOnNextDeploy: true, config: {}, secretRefs: {} },
        ])
      }
      if (String(input) === '/api/v1/environments/runtime') return response([
        { environmentId: 'win-1', provider: 'HYPER_V', state: 'SAVED', controllable: true, message: 'Hyper-V · TF-WIN-A' },
        { environmentId: 'android-1', provider: 'EXTERNAL', state: 'UNMANAGED', controllable: false, message: '生命周期由外部基础设施管理' },
      ])
      if (String(input) === '/api/v1/environments/runtime/win-1/start' && init?.method === 'POST') {
        return response({ environmentId: 'win-1', provider: 'HYPER_V', state: 'RUNNING', controllable: true, message: '虚拟机启动命令已完成' })
      }
      throw new Error(`unexpected URL: ${String(input)}`)
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = mount(EnvironmentManagement)
    await flushPromises()

    expect(wrapper.text()).toContain('ENVIRONMENTS 2')
    expect(wrapper.text()).toContain('Windows VM 01')
    expect(wrapper.text()).toContain('Android Emulator 01')
    expect(wrapper.text()).toContain('一台 VM、模拟器或设备对应一个环境实例')
    expect(wrapper.text()).toContain('Test Job 只选择系统类型')
    expect(wrapper.text()).toContain('SAVED')

    await wrapper.findAll('.run-row').find(button => button.text().includes('Windows VM 01'))!.trigger('click')
    await wrapper.findAll('button').find(button => button.text() === '启动环境')!.trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/environments/runtime/win-1/start', expect.objectContaining({ method: 'POST' }))
    expect(wrapper.text()).toContain('Windows VM 01 已启动')
    expect(wrapper.text()).toContain('RUNNING')
  })
})
