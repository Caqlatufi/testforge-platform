<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { api, ApiError } from "../common";
import {
  projectApi,
  type Project,
  type RevisionType,
} from "../projects";
import type { EnvironmentPlatform } from "../environments";
import type { WorkflowView } from "../workflows";
import WorkflowDesigner from "../workflows/WorkflowDesigner.vue";
import RevisionPicker from "./RevisionPicker.vue";
import { testJobApi, type TestJob } from ".";

const emit = defineEmits<{ "workspace-change": [active: boolean] }>();
const projects = ref<Project[]>([]),
  workflows = ref<WorkflowView[]>([]),
  jobs = ref<TestJob[]>([]);
const selected = ref<TestJob | null>(null),
  busy = ref(false),
  error = ref(""),
  notice = ref("");
const form = reactive({
  id: "",
  projectId: "",
  name: "",
  description: "",
  workflowId: "",
  revisionType: "BRANCH" as RevisionType,
  revisionValue: "",
  priority: 5 as 5 | 9,
  configVersion: 0,
});
const platformFilter = ref<EnvironmentPlatform>("WINDOWS");
const showWorkflowEditor = ref(false), editingWorkflowId = ref("");
const locked = computed(() => selected.value !== null && selected.value.state !== "DRAFT");
function message(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error
    ? cause.message
    : String(cause);
}
function reset() {
  Object.assign(form, {
    id: "",
    name: "",
    description: "",
    workflowId: "",
    revisionType: "BRANCH",
    revisionValue: "",
    priority: 5,
    configVersion: 0,
  });
  selected.value = null;
  closeWorkflow();
}
async function load() {
  busy.value = true;
  error.value = "";
  try {
    projects.value = await projectApi.list();
    jobs.value = await testJobApi.list();
    if (!form.projectId && projects.value[0]) {
      form.projectId = projects.value[0].id;
      await projectChanged();
    }
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
async function projectChanged() {
  form.workflowId = "";
  form.revisionType = "BRANCH";
  form.revisionValue = projects.value.find(item => item.id === form.projectId)?.defaultBranch ?? "";
  closeWorkflow();
  workflows.value = form.projectId
    ? await api<WorkflowView[]>(`/api/v1/projects/${form.projectId}/workflows`)
    : [];
  const latest = workflows.value.filter(item => item.latestVersion > 0).sort((left, right) => {
    const leftTime = Date.parse(left.updatedAt ?? ""), rightTime = Date.parse(right.updatedAt ?? "");
    return Number.isNaN(leftTime) || Number.isNaN(rightTime) || leftTime === rightTime
      ? right.latestVersion - left.latestVersion
      : rightTime - leftTime;
  })[0];
  form.workflowId = latest?.id ?? "";
}
function revisionSelected(selection: { type: "BRANCH" | "TAG"; value: string }) {
  form.revisionType = selection.type;
  form.revisionValue = selection.value;
}
function createWorkflow() {
  editingWorkflowId.value = "";
  showWorkflowEditor.value = true;
  emit("workspace-change", true);
}
function editWorkflow() {
  editingWorkflowId.value = form.workflowId;
  showWorkflowEditor.value = true;
  emit("workspace-change", true);
}
function workflowCreated(value: WorkflowView) {
  editingWorkflowId.value = value.id;
}
function closeWorkflow() {
  showWorkflowEditor.value = false;
  editingWorkflowId.value = "";
  emit("workspace-change", false);
}
async function workflowPublished(value: { workflowId: string; version: number }) {
  workflows.value = await api<WorkflowView[]>(`/api/v1/projects/${form.projectId}/workflows`);
  form.workflowId = value.workflowId;
  editingWorkflowId.value = value.workflowId;
  notice.value = `Workflow v${value.version} 已发布并绑定到当前测试任务`;
  closeWorkflow();
}
function edit(job: TestJob) {
  selected.value = job;
  Object.assign(form, {
    id: job.id,
    projectId: job.projectId,
    name: job.name,
    description: job.description ?? "",
    workflowId: job.workflowId,
    revisionType: job.revisionType,
    revisionValue: job.revisionValue ?? "",
    priority: job.priority === 9 ? 9 : 5,
    configVersion: job.configVersion,
  });
  projectChanged()
    .then(() => {
      form.workflowId = job.workflowId;
      form.revisionType = job.revisionType === "TAG" ? "TAG" : "BRANCH";
      form.revisionValue = job.revisionType === "DEFAULT_BRANCH"
        ? projects.value.find(item => item.id === job.projectId)?.defaultBranch ?? ""
        : job.revisionValue ?? "";
      platformFilter.value = job.platform ?? "WINDOWS";
    });
}
async function perform(action: () => Promise<unknown>, text: string) {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    await action();
    notice.value = text;
    jobs.value = await testJobApi.list();
    if (form.id) {
      const updated = jobs.value.find((item) => item.id === form.id);
      if (updated) edit(updated);
    }
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
async function save() {
  await perform(async () => {
    const saved = await testJobApi.save({ ...form, platform: platformFilter.value, id: form.id || undefined });
    edit(saved);
  }, "测试任务已保存为 DRAFT");
}
async function executeTask() {
  if (!selected.value) return;
  const firstExecution = selected.value.state === "DRAFT";
  await perform(async () => {
    let task = selected.value!;
    if (task.state === "DRAFT") task = await testJobApi.activate(task);
    await testJobApi.execute(task.id);
  }, firstExecution ? "测试任务已确认并开始执行" : "测试任务已重新执行");
}
async function copyTask() {
  if (!selected.value) return;
  busy.value = true; error.value = ""; notice.value = "";
  try {
    const copied = await testJobApi.copy(selected.value.id);
    jobs.value = await testJobApi.list();
    edit(copied);
    notice.value = `已复制为 ${copied.name}，可选择新的版本后执行`;
  } catch (cause) { error.value = message(cause); }
  finally { busy.value = false; }
}
onMounted(load);
</script>

<template>
  <WorkflowDesigner
    v-if="showWorkflowEditor"
    :project-id="form.projectId"
    :workflow-id="editingWorkflowId || undefined"
    @back="closeWorkflow"
    @created="workflowCreated"
    @published="workflowPublished"
  />
  <template v-else>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success">{{ notice }}</p>
    <section class="dashboard asset-layout">
    <aside class="panel asset-list">
      <div class="section-title">
        <h2>
          Test Jobs <span class="count">{{ jobs.length }}</span>
        </h2>
        <button class="ghost" @click="reset">新建</button>
      </div>
      <button
        v-for="job in jobs"
        :key="job.id"
        class="run-row"
        :class="{ active: selected?.id === job.id }"
        @click="edit(job)"
      >
        <strong>{{ job.name }}</strong
        ><span :class="`status-${job.state.toLowerCase()}`">{{
          job.state === 'DRAFT' ? '草稿' : job.state === 'ACTIVE' ? '已确认' : '已停用'
        }}</span
        ><small>{{ job.resolvedCommit?.slice(0, 10) ?? '待固化 Commit' }} · {{ job.platform }} · Workflow v{{ job.workflowVersion }}</small>
      </button>
      <p v-if="!jobs.length" class="empty">还没有可重复执行的测试任务。</p>
    </aside>
    <article class="panel">
      <div class="section-title">
        <h2>{{ form.id ? "测试任务配置" : "创建测试任务" }}</h2>
        <span class="connection">{{ selected?.state ?? "DRAFT" }}</span>
      </div>
      <form class="form-grid test-job-form" @submit.prevent="save">
        <section class="job-form-section job-form-identity">
          <header class="job-form-section-heading">
            <span>01</span>
            <div><h3>任务定义</h3><small>确认任务归属与用途</small></div>
          </header>
          <div class="job-fields">
            <label class="job-name-field">任务名称<input v-model.trim="form.name" required :disabled="locked" placeholder="例如：Windows 主流程回归" /></label>
            <label
              >被测项目<select
                v-model="form.projectId"
                required
                :disabled="locked"
                @change="projectChanged"
              >
                <option value="" disabled>选择被测项目</option>
                <option
                  v-for="project in projects"
                  :key="project.id"
                  :value="project.id"
                >
                  {{ project.name }}
                </option>
              </select></label
            >
            <label>说明<input v-model.trim="form.description" :disabled="locked" placeholder="可选：说明本任务覆盖的业务目标" /></label>
          </div>
        </section>

        <section class="job-form-section job-form-content">
          <header class="job-form-section-heading">
            <span>02</span>
            <div><h3>测试内容</h3><small>选择工作流与被测代码版本</small></div>
          </header>
          <div class="workflow-select-row">
            <label>已发布 Workflow<select v-model="form.workflowId" required :disabled="locked">
                <option value="" disabled>选择 Workflow</option>
                <option v-for="workflow in workflows.filter(item => item.latestVersion > 0)" :key="workflow.id" :value="workflow.id">
                  {{ workflow.name }} · v{{ workflow.latestVersion }}
                </option>
              </select><small>默认使用项目最近发布的工作流</small></label>
            <div class="workflow-action-stack">
              <button type="button" class="ghost workflow-create-action" :disabled="locked || !form.projectId" @click="createWorkflow">新建 Workflow</button>
              <button type="button" class="ghost workflow-edit-action" :disabled="locked || !form.workflowId" @click="editWorkflow">查看 / 编辑草稿</button>
            </div>
          </div>
          <div class="form-field revision-field"><span>被测版本</span><RevisionPicker
              :project-id="form.projectId"
              :default-branch="projects.find(item => item.id === form.projectId)?.defaultBranch"
              :type="form.revisionType"
              :value="form.revisionValue"
              :disabled="locked"
              @select="revisionSelected"
            /></div>
        </section>

        <section class="job-form-section job-form-execution">
          <header class="job-form-section-heading">
            <span>03</span>
            <div><h3>执行配置</h3><small>平台按目标系统和实时负载自动分配测试机</small></div>
          </header>
          <div class="job-execution-fields">
            <label
              >目标系统<select v-model="platformFilter" :disabled="locked">
                <option value="WINDOWS">Windows</option>
                <option value="ANDROID">Android</option>
                <option value="IOS">iOS</option>
              </select></label
            ><label
              >优先级<select v-model.number="form.priority" :disabled="locked">
                <option :value="5">普通</option>
                <option :value="9">紧急</option>
              </select></label
            ><div class="job-save-action">
              <small>{{ locked ? '任务已经确认，测试对象已冻结；如需修改请复制任务。' : '保存后固化当前 Commit，确认执行后不可修改。' }}</small>
              <button class="primary" :disabled="busy || locked">保存为 DRAFT</button>
            </div>
          </div>
        </section>
      </form>
      <div v-if="selected" class="workflow-actions">
        <span>{{ selected.resolvedCommit?.slice(0, 10) }} · {{ selected.commitMessage ?? '无提交描述' }}</span>
        <button class="ghost" :disabled="busy" @click="copyTask">复制任务</button>
        <button
          class="primary"
          :disabled="busy || selected.state === 'DISABLED'"
          @click="executeTask"
        >
          {{ selected.state === 'DRAFT' ? '确认并执行' : '重新执行' }}
        </button>
      </div>
    </article>
    </section>
  </template>
</template>
