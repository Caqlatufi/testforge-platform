<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api } from '../common'
import RunReportDetail from './RunReportDetail.vue'
import type { Device, JobSummary, ResourceSnapshot, Worker } from './models'

const jobs = ref<JobSummary[]>([]), workers = ref<Worker[]>([]), devices = ref<Device[]>([])
const resources = ref<ResourceSnapshot | null>(null), reportJob = ref<JobSummary | null>(null)
const busy = ref(false), error = ref('')
function stateClass(value: string) { return `status-${value.toLowerCase().replaceAll('_', '-')}` }
function phase(job: JobSummary) { return job.currentAttemptPhase ?? job.currentAttemptState ?? job.taskState }
function displayState(job: JobSummary) {
  if (phase(job) === 'WAITING_RESOURCE') return job.waitingExecutor ? `等待 ${job.waitingExecutor} 执行槽` : '等待兼容执行器'
  return ({ WAITING_DEPLOYMENT: '等待 Jenkins 发布', WAITING_DEPENDENCY: '等待前置 Case', QUEUED: '等待调度', RUNNING: '执行中', SUCCEEDED: '已成功', FAILED: '失败', BLOCKED: '已阻断', CANCELLED: '已取消', DRAFT: '草稿' } as Record<string, string>)[phase(job)] ?? phase(job)
}
function queuedCases(snapshot: ResourceSnapshot) { return snapshot.processQueued + snapshot.deviceQueued }
function runningCases(snapshot: ResourceSnapshot) { return snapshot.processRunning + snapshot.deviceRunning }
function availableProcessSlots(snapshot: ResourceSnapshot) { return Math.max(0, snapshot.processCapacity - snapshot.processRunning) }
function executorCapacity(snapshot: ResourceSnapshot) {
  const entries = Object.entries(snapshot.executorCapacity ?? {}).filter(([runner]) => runner !== 'airtest')
  return entries.length ? entries.map(([runner, capacity]) => `${runner} ${capacity}`).join(' · ') : '没有在线脚本执行器'
}
function displayTime(value?: string) { return value ? new Date(value).toLocaleString() : '尚未执行' }
async function refresh() {
  busy.value = true; error.value = ''
  try {
    [jobs.value, workers.value, devices.value, resources.value] = await Promise.all([
      api<JobSummary[]>('/api/v1/test-jobs/execution-summaries'), api<Worker[]>('/api/v1/workers'),
      api<Device[]>('/api/v1/device-slots'), api<ResourceSnapshot>('/api/v1/monitoring/resources'),
    ])
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { busy.value = false }
}
async function cancel(job: JobSummary) {
  if (!job.currentAttemptId) return
  busy.value = true; error.value = ''
  try {
    await api(`/api/v1/runs/${job.currentAttemptId}/cancel`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID(), reason: '用户从运行与报告页面取消' }) })
    await refresh()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause); busy.value = false }
}
function openReport(job: JobSummary) { if (job.currentAttemptId) reportJob.value = job }
function closeReport() { reportJob.value = null; void refresh() }
onMounted(refresh)
</script>

<template>
  <RunReportDetail v-if="reportJob" :job="reportJob" @back="closeReport" />
  <template v-else>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <section v-if="busy && !resources" class="panel"><p class="empty">正在加载执行资源与测试任务……</p></section>
    <section v-if="resources" class="panel operations-resources">
      <div class="section-title"><div><p class="section-kicker">CAPACITY / QUEUES</p><h2>执行资源与队列</h2><small>全局容量只用于判断当前能否调度；具体流程与结果在任务报告中查看。</small></div><button class="ghost" :disabled="busy" @click="refresh">刷新</button></div>
      <div class="stats resource-stats">
        <div><span>等待发布 Case</span><strong>{{ resources.deploymentWaiting }}</strong><small>等待 Jenkins 准备测试环境</small></div>
        <div><span>待调度 Case</span><strong>{{ queuedCases(resources) }}</strong><small>脚本 {{ resources.processQueued }} · UI {{ resources.deviceQueued }}</small></div>
        <div><span>执行中 Case</span><strong>{{ runningCases(resources) }}</strong><small>脚本 {{ resources.processRunning }} · UI {{ resources.deviceRunning }}</small></div>
        <div><span>脚本执行槽</span><strong>{{ availableProcessSlots(resources) }}/{{ resources.processCapacity }}</strong><small>{{ executorCapacity(resources) }}</small></div>
        <div><span>UI 测试机</span><strong>{{ resources.deviceAvailable }}/{{ resources.deviceCapacity }}</strong><small>可用 / 在线 · Agent {{ resources.onlineWorkers }}/{{ workers.length }}</small></div>
      </div>
      <details class="resource-inventory"><summary>查看 Agent 与交互式测试目标</summary><div class="dashboard"><div><h3>Environment Agents</h3><div v-for="worker in workers" :key="worker.workerId" class="resource-card"><strong>{{ worker.workerId }}</strong><small>{{ worker.status }} · 并发槽 {{ worker.maxConcurrency }} · {{ worker.capabilities.join(' / ') }}</small></div></div><div><h3>交互式测试目标</h3><div v-for="device in devices" :key="device.slotId" class="resource-card"><strong>{{ device.deviceId }}</strong><small>{{ device.platform }} · {{ device.status }} · Agent {{ device.workerId }}</small></div></div></div></details>
    </section>
    <section class="panel operations-jobs">
      <div class="section-title"><div><p class="section-kicker">TEST JOBS / REPORTS</p><h2>测试任务运行与报告 <span class="count">{{ jobs.length }}</span></h2><small>一行对应一个测试任务；执行中的任务优先，其余按最近执行时间排列。</small></div></div>
      <div v-if="jobs.length" class="job-run-table" role="table" aria-label="测试任务运行与报告">
        <div class="job-run-head" role="row"><span>测试任务</span><span>固化版本</span><span>运行状态</span><span>最近执行</span><span>操作</span></div>
        <article v-for="job in jobs" :key="job.taskId" class="job-run-row" :class="{ active: job.activeAttemptCount > 0 }" role="row">
          <div class="job-run-identity"><strong>{{ job.name }}</strong><small>{{ job.projectName }}</small></div>
          <div class="job-run-revision"><strong class="mono">{{ job.resolvedCommit?.slice(0, 12) ?? '待固化 Commit' }}</strong><small>Workflow v{{ job.workflowVersion || '—' }}</small><small>{{ job.commitMessage || '暂无 Commit 描述' }}</small></div>
          <div class="job-run-state"><strong :class="stateClass(phase(job))">{{ displayState(job) }}</strong><span class="progress-track" aria-hidden="true"><i :style="{ width: `${job.progress}%` }"></i></span><small>{{ job.progress }}% · 第 {{ job.attemptCount }} 次执行</small></div>
          <div class="job-run-time"><strong>{{ displayTime(job.lastExecutedAt) }}</strong><small v-if="job.activeAttemptCount">{{ job.activeAttemptCount }} 个执行中</small><small v-else>当前无活动执行</small></div>
          <div class="job-run-action"><button class="ghost" :disabled="!job.currentAttemptId" @click="openReport(job)">查看报告</button><button v-if="job.activeAttemptCount" class="ghost danger" :disabled="busy" @click="cancel(job)">取消执行</button><small v-if="!job.currentAttemptId">尚无运行记录</small></div>
        </article>
      </div>
      <p v-else-if="!busy" class="empty">没有测试任务。请先在“测试任务”页面创建并执行。</p>
    </section>
  </template>
</template>
