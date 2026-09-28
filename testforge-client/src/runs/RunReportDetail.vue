<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { VueFlow, type Edge, type Node } from '@vue-flow/core'
import { Controls } from '@vue-flow/controls'
import { api } from '../common'
import { diagnoseReport, getAiDiagnosisStatus, getLatestAiDiagnosis, type AiDiagnosis, type AiDiagnosisStatus } from '../reports'
import { withVirtualTopology } from './graph'
import { RunEventClient, type ConnectionState } from './live'
import type { Graph, GraphNode, JobSummary, Report, Run, Task } from './models'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'

const props = defineProps<{ job: JobSummary }>()
const emit = defineEmits<{ back: [] }>()
const attempts = ref<Run[]>([]), selected = ref<Run | null>(null), graph = ref<Graph | null>(null)
const report = ref<Report | null>(null), selectedNode = ref<GraphNode | null>(null)
const diagnosis = ref<AiDiagnosis | null>(null), connection = ref<ConnectionState>('closed')
const aiStatus = ref<AiDiagnosisStatus | null>(null)
const busy = ref(false), aiBusy = ref(false), error = ref('')
let stream: RunEventClient<Run> | null = null, selectionVersion = 0
const terminal = new Set(['SUCCEEDED', 'FAILED', 'COMPLETED_WITH_WARNINGS', 'CANCELLED'])
function stateClass(value: string) { return `status-${value.toLowerCase().replaceAll('_', '-')}` }
function stateLabel(value: string) { return ({ WAITING_DEPLOYMENT: '等待 Jenkins 发布', WAITING_DEPENDENCY: '等待前置 Case', WAITING_RESOURCE: '等待兼容执行器', QUEUED: '等待调度', RUNNING: '执行中', SUCCEEDED: '已成功', FAILED: '失败', BLOCKED: '已阻断', CANCELLED: '已取消' } as Record<string, string>)[value] ?? value }
function runPhase(run: Run | null) {
  if (!run) return props.job.currentAttemptPhase ?? props.job.currentAttemptState ?? props.job.taskState
  if ((run.taskCounts.WAITING_DEPLOYMENT ?? 0) > 0) return 'WAITING_DEPLOYMENT'
  if ((run.taskCounts.WAITING_DEPENDENCY ?? 0) > 0) return 'WAITING_DEPENDENCY'
  if ((run.taskCounts.QUEUED ?? 0) > 0) return 'QUEUED'
  return run.state
}
const runtimeTopology = computed(() => withVirtualTopology(
  (graph.value?.nodes ?? []).map<Node>(item => ({ id: item.id, position: { x: item.positionX, y: item.positionY }, data: { label: `${item.label}\n${item.completedTasks}/${item.totalTasks} · ${stateLabel(item.state)}` }, class: stateClass(item.state) })),
  (graph.value?.edges ?? []).map<Edge>(item => ({ id: `${item.sourceNodeId}-${item.targetNodeId}`, source: item.sourceNodeId, target: item.targetNodeId, label: item.condition })),
))
const graphNodes = computed(() => runtimeTopology.value.nodes), graphEdges = computed(() => runtimeTopology.value.edges)
const selectedTasks = computed(() => selectedNode.value?.taskIds.map(id => selected.value?.tasks.find(task => task.id === id)).filter((item): item is Task => !!item) ?? [])
const passRate = computed(() => report.value?.total ? Math.round(report.value.passed / report.value.total * 100) : 0)
const aiUnavailableReason = computed(() => !report.value ? '报告尚未生成' : aiStatus.value && !aiStatus.value.available ? aiStatus.value.message : '')
function artifactName(key: string) { return key.split('/').pop() || key }
function artifactType(key: string) { const name = artifactName(key).toLowerCase(); if (name.includes('trace')) return 'TRACE'; if (/\.(png|jpg|jpeg|webp)$/.test(name)) return 'SCREENSHOT'; if (/\.(log|txt)$/.test(name)) return 'LOG'; if (name.endsWith('.xml')) return 'JUNIT'; return 'ATTACHMENT' }
async function optional<T>(path: string) { try { return await api<T>(path) } catch { return null } }
async function load() {
  busy.value = true; error.value = ''
  try {
    attempts.value = await api<Run[]>(`/api/v1/test-jobs/${props.job.taskId}/attempts`)
    const preferred = attempts.value.find(item => item.id === props.job.currentAttemptId) ?? attempts.value.find(item => !terminal.has(item.state)) ?? attempts.value[0]
    if (preferred) await selectAttempt(preferred.id)
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { busy.value = false }
}
async function selectAttempt(runId: string) {
  const generation = ++selectionVersion; stream?.close(); selected.value = null; graph.value = null; report.value = null; diagnosis.value = null; selectedNode.value = null; error.value = ''
  try {
    const [run, nextGraph] = await Promise.all([api<Run>(`/api/v1/runs/${runId}`), api<Graph>(`/api/v1/runs/${runId}/graph`)])
    if (generation !== selectionVersion || run.testJobId !== props.job.taskId) return
    selected.value = run; graph.value = nextGraph; report.value = await optional<Report>(`/api/v1/reports/runs/${runId}`); diagnosis.value = await getLatestAiDiagnosis(runId); aiStatus.value = report.value ? await optional<AiDiagnosisStatus>(`/api/v1/reports/${runId}/diagnosis/status`) : null
    stream = new RunEventClient(runId, value => {
      if (generation === selectionVersion && value.id === runId && value.testJobId === props.job.taskId) { selected.value = value; if (terminal.has(value.state)) stream?.close() }
    }, () => api<Run>(`/api/v1/runs/${runId}`), value => { if (generation === selectionVersion) connection.value = value })
    stream.connect()
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) }
}
function attemptNumber(runId: string) { const index = attempts.value.findIndex(item => item.id === runId); return index < 0 ? 1 : attempts.value.length - index }
function pickNode(id: string) { selectedNode.value = graph.value?.nodes.find(node => node.id === id) ?? null }
async function generateDiagnosis() {
  if (!report.value) return
  aiBusy.value = true
  try { diagnosis.value = await diagnoseReport(report.value.runId, diagnosis.value !== null) } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { aiBusy.value = false }
}
function goBack() { stream?.close(); emit('back') }
onMounted(load); onBeforeUnmount(() => stream?.close())
</script>

<template>
  <section class="run-report-header">
    <button class="ghost" @click="goBack">← 返回运行与报告</button>
    <div><p class="section-kicker">TEST JOB / RUN REPORT</p><h2>{{ job.name }}</h2><small>{{ job.projectName }} · <span class="mono">{{ job.resolvedCommit?.slice(0, 12) ?? '待固化 Commit' }}</span> · Workflow v{{ job.workflowVersion }}</small><small>{{ job.commitMessage }}</small></div>
    <div class="report-header-state"><strong :class="stateClass(runPhase(selected))">{{ stateLabel(runPhase(selected)) }}</strong><span class="connection" :class="connection">SSE · {{ connection }}</span></div>
  </section>
  <p v-if="error" class="error" role="alert">{{ error }}</p><p v-if="busy" class="empty">正在加载运行报告……</p>
  <template v-if="selected">
    <section class="panel report-flow-panel">
      <div class="section-title execution-title"><div><p class="section-kicker">WORKFLOW SNAPSHOT</p><h2>执行流程</h2><small class="mono">第 {{ attemptNumber(selected.id) }} 次执行 · Run {{ selected.id.slice(0, 12) }} · Workflow v{{ selected.workflowVersion }}</small></div><details v-if="attempts.length > 1" class="attempt-history"><summary>执行记录 {{ attempts.length }}</summary><div><button v-for="attempt in attempts" :key="attempt.id" type="button" :class="{ active: selected.id === attempt.id }" @click="selectAttempt(attempt.id)"><strong>第 {{ attemptNumber(attempt.id) }} 次</strong><span :class="stateClass(attempt.state)">{{ attempt.state }}</span><small>{{ new Date(attempt.createdAt).toLocaleString() }}</small></button></div></details></div>
      <div class="workflow-canvas runtime-graph"><VueFlow :nodes="graphNodes" :edges="graphEdges" fit-view-on-init :nodes-draggable="false" :nodes-connectable="false" @node-click="pickNode($event.node.id)"><Controls /></VueFlow></div>
      <p v-if="graph?.compiledFallback" class="error">该历史版本没有展示快照，当前使用编译图兼容展示。</p>
      <div v-if="selectedNode" class="subsection node-execution-detail"><h3>{{ selectedNode.label }} · {{ stateLabel(selectedNode.state) }}</h3><div v-for="task in selectedTasks" :key="task.id" class="timeline"><div><span>Execution Task · {{ task.sourceRef }}</span><time>{{ stateLabel(task.state) }}</time></div><div><span>资源需求 · {{ task.interactionMode }} / {{ task.resourceProfile }}</span><time>{{ task.schedulingWaitReason ?? 'READY' }}</time></div><div><span>租约 · {{ task.leaseScope }}</span><time>{{ task.resourceSessionKey ?? '当前 Case' }}</time></div><div v-if="task.targetRevision"><span>Commit</span><time class="mono">{{ task.targetRevision.resolvedCommit.slice(0, 12) }}</time></div><div v-for="attempt in task.attempts" :key="attempt.id"><span>Environment Agent Attempt #{{ attempt.attemptNo }} · {{ attempt.workerId }}</span><time>{{ attempt.state }}</time></div></div></div>
      <p v-else class="empty report-node-hint">点击流程节点查看该 Case 的调度、资源租约和 Attempt 时间线。</p>
    </section>
    <section class="panel report-results-panel">
      <div class="section-title"><div><p class="section-kicker">RESULTS / EVIDENCE</p><h2>运行报告</h2></div><div><button class="ghost" :disabled="!report || aiBusy || aiStatus?.available === false" :title="aiUnavailableReason" @click="generateDiagnosis">{{ diagnosis ? '重新诊断' : 'AI 辅助诊断' }}</button><small v-if="aiUnavailableReason">{{ aiUnavailableReason }}</small></div></div>
      <template v-if="report"><div class="stats"><div><span>通过率</span><strong>{{ passRate }}%</strong></div><div><span>通过</span><strong>{{ report.passed }}</strong></div><div><span>失败</span><strong>{{ report.failed }}</strong></div><div><span>P95</span><strong>{{ report.p95DurationMs }} ms</strong></div></div><p v-if="diagnosis" class="diagnosis-summary"><strong>{{ diagnosis.category }}</strong> · {{ diagnosis.suggestions.join('；') }}</p><table><thead><tr><th>Task</th><th>Attempt</th><th>结果</th><th>耗时</th><th>摘要</th></tr></thead><tbody><tr v-for="item in report.results" :key="item.attemptId"><td class="mono">{{ item.taskId.slice(0, 8) }}</td><td class="mono">{{ item.attemptId.slice(0, 8) }}</td><td :class="stateClass(item.status)">{{ item.status }}</td><td>{{ item.durationMs }} ms</td><td>{{ item.summary }}</td></tr></tbody></table><div class="subsection"><h3>Evidence 附件</h3><div v-if="report.artifactKeys.length" class="evidence-list"><div v-for="key in report.artifactKeys" :key="key" class="resource-card"><strong>{{ artifactType(key) }} · {{ artifactName(key) }}</strong><small class="mono">{{ key }}</small></div></div><p v-else class="empty">本次执行没有上传截图、Trace 或日志附件。</p></div></template>
      <p v-else class="empty">该执行尚未生成完整报告。执行结束后刷新即可查看确定性结果和 AI 诊断。</p>
    </section>
  </template>
  <section v-else-if="!busy" class="panel"><p class="empty">该测试任务尚无可查看的执行记录。</p></section>
</template>
