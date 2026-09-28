<script setup lang="ts">
import { ref } from 'vue'
import AssetManagement from '../projects/AssetManagement.vue'
import RunCenter from '../runs/RunCenter.vue'
import TestJobManagement from '../jobs/TestJobManagement.vue'
import EnvironmentManagement from '../environments/EnvironmentManagement.vue'

const requestedView = new URLSearchParams(window.location.search).get('view')
const view = ref<'assets' | 'environments' | 'jobs' | 'runs'>(requestedView === 'runs' || requestedView === 'assets' || requestedView === 'environments' ? requestedView : 'jobs')
const workflowWorkspace = ref(false)
const testJobViewKey = ref(0)

if (requestedView !== view.value) {
  const url = new URL(window.location.href)
  url.searchParams.set('view', view.value)
  window.history.replaceState({ view: view.value }, '', url)
}

function selectView(next: 'assets' | 'environments' | 'jobs' | 'runs') {
  if (next === 'jobs' && view.value === 'jobs' && workflowWorkspace.value) testJobViewKey.value += 1
  workflowWorkspace.value = false
  view.value = next
  const url = new URL(window.location.href)
  url.searchParams.set('view', next)
  if (next !== 'assets') url.searchParams.delete('project')
  window.history.pushState({ view: next }, '', url)
}

window.addEventListener('popstate', () => {
  const next = new URLSearchParams(window.location.search).get('view')
  if (next === 'runs' || next === 'assets' || next === 'environments' || next === 'jobs') view.value = next
})
</script>

<template>
  <main class="shell" :class="{ 'workspace-mode': workflowWorkspace }">
    <header class="hero">
      <div class="brand-lockup" aria-label="TestForge">
        <span class="brand-mark"><i>TF</i></span>
        <span class="brand-name">TESTFORGE<small>QUALITY ENGINEERING</small></span>
      </div>
      <div class="hero-copy">
        <p class="eyebrow"><span>SYS / 00</span> TEST AUTOMATION CONTROL PLANE</p>
        <h1><span>TestForge</span><em>Console</em></h1>
        <p class="summary">真实 API 驱动的资产管理、运行与报告、Environment Agent 运维面板与完整证据链。</p>
      </div>
      <div class="hero-status" aria-label="系统状态">
        <div><span class="status-pulse"></span><strong>WEB CONSOLE</strong><small>ACTIVE</small></div>
        <div><strong>LOCAL / CN</strong><small>UTC +08:00</small></div>
        <b>01</b>
      </div>
    </header>
    <nav class="view-tabs" aria-label="主功能导航">
      <span class="nav-coordinate">MODULE<br><b>SELECT</b></span>
      <button data-index="01" :class="{ active: view === 'jobs' }" @click="selectView('jobs')"><span>测试任务</span><small>TEST JOBS</small></button>
      <button data-index="02" :class="{ active: view === 'runs' }" @click="selectView('runs')"><span>运行与报告</span><small>OPERATIONS / REPORTS</small></button>
      <button data-index="03" :class="{ active: view === 'assets' }" @click="selectView('assets')"><span>被测项目</span><small>PROJECTS</small></button>
      <button data-index="04" :class="{ active: view === 'environments' }" @click="selectView('environments')"><span>环境资源</span><small>ENVIRONMENTS</small></button>
      <span class="nav-tail">TESTFORGE<br>OPS // 2026</span>
    </nav>
    <div class="blueprint-rule"><span>TF // AUTOMATION INFRASTRUCTURE</span><i></i><b>READY</b></div>
    <TestJobManagement v-if="view === 'jobs'" :key="testJobViewKey" @workspace-change="workflowWorkspace = $event" />
    <RunCenter v-else-if="view === 'runs'" />
    <AssetManagement v-else-if="view === 'assets'" />
    <EnvironmentManagement v-else />
  </main>
</template>
