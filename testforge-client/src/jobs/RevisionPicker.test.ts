import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import RevisionPicker from './RevisionPicker.vue'

function response(data: unknown) {
  return Promise.resolve(new Response(JSON.stringify({ data }), { status: 200, headers: { 'Content-Type': 'application/json' } }))
}

afterEach(() => vi.unstubAllGlobals())

describe('RevisionPicker', () => {
  it('defaults to the repository default branch and selects a tag with commit metadata', async () => {
    vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
      const url = String(input)
      if (url.endsWith('type=BRANCH')) return response([{ type: 'BRANCH', name: 'main', commitSha: 'a'.repeat(40), commitMessage: 'branch head', committedAt: '2026-09-24T00:00:00Z', defaultBranch: true }])
      if (url.endsWith('type=TAG')) return response([{ type: 'TAG', name: 'v1.0.0', commitSha: 'b'.repeat(40), commitMessage: 'release build', committedAt: '2026-09-23T00:00:00Z', defaultBranch: false }])
      throw new Error(`unexpected URL: ${url}`)
    }))
    const wrapper = mount(RevisionPicker, { props: { projectId: 'p1', defaultBranch: 'main', type: 'BRANCH', value: '' } })
    await flushPromises()

    expect(wrapper.emitted('select')).toContainEqual([{ type: 'BRANCH', value: 'main', option: expect.objectContaining({ commitMessage: 'branch head' }) }])
    await wrapper.find('.revision-trigger').trigger('click')
    await wrapper.findAll('.revision-tabs button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('release build')
    await wrapper.find('.revision-options button').trigger('click')
    expect(wrapper.emitted('select')).toContainEqual([{ type: 'TAG', value: 'v1.0.0', option: expect.objectContaining({ commitSha: 'b'.repeat(40) }) }])
  })
})
