<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { VueFlow, useVueFlow, type Connection, type Edge, type Node } from '@vue-flow/core'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { api } from '../common'
import { catalogApi, type TestCase } from '../catalog'
import { autoLayout, createsCycle, type WorkflowNodeData, type WorkflowNodeType, type WorkflowView } from '.'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

const props = defineProps<{ projectId: string; workflowId?: string }>()
const emit = defineEmits<{ back: []; published: [value: { workflowId: string; version: number }]; created: [value: WorkflowView] }>()
type LibraryItem = { id: string; version: number; name: string; type: Exclude<WorkflowNodeType, 'SUITE'>; source: 'SHARED' | 'PROJECT' | 'SUBFLOW' }

const nodes = ref<Node<WorkflowNodeData>[]>([]), edges = ref<Edge[]>([])
const selectedId = ref(''), error = ref(''), notice = ref(''), busy = ref(false)
const workflow = reactive({ id: '', name: '' })
const revision = ref(0), history = ref<string[]>([]), future = ref<string[]>([])
const library = ref<LibraryItem[]>([])
const selected = computed(() => nodes.value.find(node => node.id === selectedId.value))
const selectedData = computed(() => selected.value?.data as WorkflowNodeData | undefined)
const grouped = computed(() => ({
  shared: library.value.filter(item => item.source === 'SHARED'),
  project: library.value.filter(item => item.source === 'PROJECT'),
  subflows: library.value.filter(item => item.source === 'SUBFLOW'),
}))
const { fitView } = useVueFlow()
const nodeHelp: Record<'CASE' | 'FIXTURE' | 'SUBFLOW', string> = {
  CASE: '执行该 Workflow 发布时固化的 Case YAML 与脚本资源。',
  FIXTURE: '准备或清理测试数据与环境。',
  SUBFLOW: '复用当前项目已经发布的工作流。',
}

function snapshot() { return JSON.stringify({ nodes: nodes.value, edges: edges.value }) }
function restore(value: string) { const parsed = JSON.parse(value); nodes.value = parsed.nodes; edges.value = parsed.edges }
function checkpoint() { history.value.push(snapshot()); if (history.value.length > 30) history.value.shift(); future.value = [] }
function undo() { const value = history.value.pop(); if (!value) return; future.value.push(snapshot()); restore(value) }
function redo() { const value = future.value.pop(); if (!value) return; history.value.push(snapshot()); restore(value) }
function addAsset(item: LibraryItem) {
  checkpoint(); const id = crypto.randomUUID()
  nodes.value.push({ id, position: { x: 80 + nodes.value.length * 28, y: 70 + nodes.value.length * 44 }, data: { label: item.name, type: item.type, referenceId: item.id, referenceVersion: item.version, required: true, timeoutSeconds: 60, parameterOverrides: '{}' } })
  selectedId.value = id
}
function onConnect(connection: Connection) {
  if (!connection.source || !connection.target || createsCycle(nodes.value, edges.value, connection.source, connection.target)) { error.value = '该连线会形成环路，已阻止'; return }
  checkpoint(); edges.value.push({ id: crypto.randomUUID(), source: connection.source, target: connection.target, label: 'ON_SUCCESS', data: { condition: 'ON_SUCCESS' } })
}
function removeSelected() { if (!selectedId.value) return; checkpoint(); nodes.value = nodes.value.filter(node => node.id !== selectedId.value); edges.value = edges.value.filter(edge => edge.source !== selectedId.value && edge.target !== selectedId.value); selectedId.value = '' }
function layout() { checkpoint(); nodes.value = autoLayout(nodes.value, edges.value) as Node<WorkflowNodeData>[]; setTimeout(() => fitView({ padding: 0.2 }), 0) }
function fromView(view: WorkflowView) {
  workflow.id = view.id; workflow.name = view.name; revision.value = view.draftRevision
  nodes.value = view.draft.nodes.map(item => ({ id: item.id, position: { x: item.positionX, y: item.positionY }, data: { label: library.value.find(asset => asset.id === item.referenceId)?.name ?? `${item.type} · ${item.referenceId.slice(0, 8)}`, type: item.type, referenceId: item.referenceId, referenceVersion: item.referenceVersion, required: item.required, timeoutSeconds: item.timeoutSeconds ?? 60, parameterOverrides: JSON.stringify(item.parameterOverrides ?? {}, null, 2) } }))
  edges.value = view.draft.edges.map(item => ({ id: `${item.predecessorNodeId}-${item.successorNodeId}`, source: item.predecessorNodeId, target: item.successorNodeId, label: item.condition, data: { condition: item.condition } }))
  history.value = []; future.value = []; setTimeout(() => fitView({ padding: 0.2 }), 0)
}
async function latestVersion(item: TestCase) {
  if (item.yamlManaged) return 1
  const versions = await catalogApi.legacyScripts(item.id)
  return versions.length ? versions[versions.length - 1].version : 0
}
async function loadLibrary() {
  if (!props.projectId) { library.value = []; return }
  const [projectCases, sharedCases, workflows] = await Promise.all([
    catalogApi.cases(props.projectId), catalogApi.sharedCases(), api<WorkflowView[]>(`/api/v1/projects/${props.projectId}/workflows`),
  ])
  const cases = [...sharedCases, ...projectCases.filter(item => item.scope !== 'SHARED')]
  const versions = await Promise.all(cases.map(latestVersion))
  const caseItems = cases.flatMap((item, index): LibraryItem[] => versions[index] < 1 ? [] : [{
    id: item.id, version: versions[index], name: item.name,
    type: item.kind === 'FIXTURE' ? 'FIXTURE' : 'CASE',
    source: item.scope === 'SHARED' ? 'SHARED' : 'PROJECT',
  }])
  const subflows: LibraryItem[] = workflows.filter(item => item.latestVersion > 0 && item.id !== workflow.id).map(item => ({ id: item.id, version: item.latestVersion, name: item.name, type: 'SUBFLOW', source: 'SUBFLOW' }))
  library.value = [...caseItems, ...subflows]
}
async function createWorkflow() {
  if (!props.projectId || !workflow.name.trim()) { error.value = '请先选择被测项目并填写 Workflow 名称'; return }
  busy.value = true; error.value = ''
  try { const view = await api<WorkflowView>(`/api/v1/projects/${props.projectId}/workflows`, { method: 'POST', body: JSON.stringify({ name: workflow.name }) }); fromView(view); emit('created', view); await loadLibrary(); notice.value = 'Workflow 草稿已创建' }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { busy.value = false }
}
async function loadWorkflow(id = props.workflowId) {
  if (!id) { workflow.id = ''; nodes.value = []; edges.value = []; revision.value = 0; return }
  busy.value = true; error.value = ''
  try { const view = await api<WorkflowView>(`/api/v1/workflows/${id}`); if (view.projectId !== props.projectId) throw new Error('Workflow 不属于当前 Project'); fromView(view); await loadLibrary() }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { busy.value = false }
}
function draftPayload() {
  return {
    expectedVersion: revision.value,
    nodes: nodes.value.map(node => {
      const data = node.data as WorkflowNodeData
      return {
        id: node.id,
        type: data.type,
        referenceId: data.referenceId,
        referenceVersion: data.referenceVersion,
        required: data.required,
        timeoutSeconds: data.timeoutSeconds,
        parameterOverrides: JSON.parse(data.parameterOverrides || '{}'),
        positionX: node.position.x,
        positionY: node.position.y,
      }
    }),
    edges: edges.value.map(edge => ({
      predecessorNodeId: edge.source,
      successorNodeId: edge.target,
      condition: edge.data?.condition ?? 'ON_SUCCESS',
    })),
  }
}
async function persistDraft() {
  const saved = await api<WorkflowView>(`/api/v1/workflows/${workflow.id}/graph`, {
    method: 'PUT',
    body: JSON.stringify(draftPayload()),
  })
  fromView(saved)
  return saved
}
async function save() {
  if (!workflow.id || !nodes.value.length) { error.value = '请先创建 Workflow，并至少加入一个真实资产节点'; return }
  busy.value = true; error.value = ''
  try {
    await persistDraft(); notice.value = '草稿已保存并通过 DAG 校验'
  } catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { busy.value = false }
}
async function publish() {
  if (!workflow.id || !nodes.value.length) { error.value = '请先创建 Workflow，并至少加入一个真实资产节点'; return }
  busy.value = true; error.value = ''
  try {
    await persistDraft()
    const result = await api<{ version: number }>(`/api/v1/workflows/${workflow.id}/publish`, { method: 'POST', body: JSON.stringify({ requestKey: crypto.randomUUID() }) })
    notice.value = `已保存草稿并发布拓扑版本 v${result.version}`
    emit('published', { workflowId: workflow.id, version: result.version })
  }
  catch (cause) { error.value = cause instanceof Error ? cause.message : String(cause) } finally { busy.value = false }
}

watch(() => [props.projectId, props.workflowId], async () => { await loadLibrary(); await loadWorkflow() })
onMounted(async () => { await loadLibrary(); await loadWorkflow() })
</script>

<template>
  <section class="workflow-page">
    <header class="workflow-page-header">
      <button class="ghost workflow-back" type="button" @click="emit('back')">← 返回测试任务</button>
      <div>
        <small>TEST JOB / WORKFLOW WORKSPACE</small>
        <h2>{{ workflow.id ? workflow.name : '新建 Workflow' }}</h2>
        <p>{{ workflow.id ? '选择真实测试资产，连线定义执行依赖。' : '先创建草稿，再进入 DAG 编排。' }}</p>
      </div>
      <span class="connection">{{ workflow.id ? `draft r${revision}` : 'NEW' }}</span>
    </header>

    <div class="workflow-feedback" aria-live="polite">
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-else-if="notice" class="success">{{ notice }}</p>
    </div>

    <section v-if="!projectId" class="workflow-create-state">
      <p class="empty">先选择被测项目，才能加载对应 Case 并编排 Workflow。</p>
    </section>
    <section v-else-if="!workflow.id" class="workflow-create-state">
      <form @submit.prevent="createWorkflow">
        <label>Workflow 名称<input v-model.trim="workflow.name" autofocus required /></label>
        <p class="empty">Workflow 自动归属当前被测项目；发布后会回填到当前测试任务。</p>
        <button class="primary" :disabled="busy">创建草稿并开始编排</button>
      </form>
    </section>

    <section v-else class="workflow-shell">
      <aside class="workflow-palette">
        <h2>节点库</h2>
        <div class="workflow-library-scroll">
          <h3>平台通用 Case</h3>
          <div v-for="item in grouped.shared" :key="`s-${item.id}`" class="node-palette-item">
            <button class="ghost library-item" @click="addAsset(item)">+ {{ item.name }}</button>
            <span class="node-help" tabindex="0">?<span class="node-tooltip"><strong>{{ item.name }}</strong><small>{{ item.type }}</small>{{ nodeHelp[item.type] }}</span></span>
          </div>
          <p v-if="!grouped.shared.length" class="empty">暂无通用 Case</p>

          <h3>项目 Case / Fixture</h3>
          <div v-for="item in grouped.project" :key="`p-${item.id}`" class="node-palette-item">
            <button class="ghost library-item" @click="addAsset(item)">+ {{ item.name }} <small>{{ item.type }}</small></button>
            <span class="node-help" tabindex="0">?<span class="node-tooltip"><strong>{{ item.name }}</strong><small>{{ item.type }}</small>{{ nodeHelp[item.type] }}</span></span>
          </div>
          <p v-if="!grouped.project.length" class="empty">暂无项目 Case</p>

          <h3>项目 Subflow</h3>
          <div v-for="item in grouped.subflows" :key="`w-${item.id}`" class="node-palette-item">
            <button class="ghost library-item" @click="addAsset(item)">+ {{ item.name }} <small>v{{ item.version }}</small></button>
            <span class="node-help" tabindex="0">?<span class="node-tooltip"><strong>{{ item.name }}</strong><small>SUBFLOW · v{{ item.version }}</small>{{ nodeHelp.SUBFLOW }}</span></span>
          </div>
          <p v-if="!grouped.subflows.length" class="empty">暂无可复用流程</p>
        </div>
        <div class="workflow-palette-actions">
          <button class="ghost" :disabled="!history.length" @click="undo">撤销</button>
          <button class="ghost" :disabled="!future.length" @click="redo">重做</button>
          <button class="ghost" @click="layout">自动布局</button>
          <button class="ghost" :disabled="!selected" @click="removeSelected">删除节点</button>
        </div>
      </aside>
      <div class="workflow-canvas"><VueFlow v-model:nodes="nodes" v-model:edges="edges" fit-view-on-init @connect="onConnect" @node-click="selectedId = $event.node.id"><MiniMap /><Controls /></VueFlow></div>
      <aside class="workflow-properties"><h2>属性</h2><template v-if="selectedData"><label>标题<input v-model="selectedData.label" /></label><label>类型<input :value="selectedData.type" readonly /></label><p v-if="selectedData.type === 'SUITE'" class="error">遗留 Suite 节点仅兼容读取，请改用多个 Case 或 Subflow。</p><label>引用<input :value="`${selectedData.referenceId}@${selectedData.referenceVersion}`" readonly /></label><label>超时秒数<input v-model.number="selectedData.timeoutSeconds" type="number" min="1" /></label><label class="check"><input v-model="selectedData.required" type="checkbox" /> 必须成功</label><label>参数覆盖 JSON<textarea v-model="selectedData.parameterOverrides" /></label></template><p v-else class="empty">选择节点后编辑属性。</p></aside>
    </section>

    <footer v-if="workflow.id" class="workflow-page-footer workflow-actions">
      <span>{{ nodes.length }} 节点 · {{ edges.length }} 条边</span>
      <button class="ghost" :disabled="busy" @click="save">保存草稿</button>
      <button class="primary" :disabled="busy || !nodes.length" @click="publish">发布并绑定任务</button>
    </footer>
  </section>
</template>
