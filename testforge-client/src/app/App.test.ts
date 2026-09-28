import { mount } from '@vue/test-utils'
import { afterEach, describe, expect, it } from 'vitest'
import App from './App.vue'

afterEach(() => window.history.replaceState({}, '', '/'))

describe('App navigation', () => {
  it('canonicalizes the default module in the URL on first load', () => {
    window.history.replaceState({}, '', '/')
    mount(App, {
      global: {
        stubs: {
          RunCenter: true,
          TestJobManagement: true,
          AssetManagement: true,
          EnvironmentManagement: true,
        },
      },
    })

    expect(new URLSearchParams(window.location.search).get('view')).toBe('jobs')
  })

  it('writes the selected primary module to the URL', async () => {
    window.history.replaceState({}, '', '/?view=runs')
    const wrapper = mount(App, {
      global: {
        stubs: {
          RunCenter: true,
          TestJobManagement: true,
          AssetManagement: true,
          EnvironmentManagement: true,
        },
      },
    })

    const projects = wrapper.findAll('nav button').find(button => button.text().includes('被测项目'))
    await projects!.trigger('click')

    expect(new URLSearchParams(window.location.search).get('view')).toBe('assets')
  })
})
