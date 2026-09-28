<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ApiError } from "../common/api";
import CaseAssetWorkbench from "../catalog/CaseAssetWorkbench.vue";
import {
  projectApi,
  type PipelineRef,
  type Project,
} from "./index";

const projects = ref<Project[]>([]),
  selected = ref<Project | null>(null);
const pipelines = ref<PipelineRef[]>([]);
const busy = ref(false),
  error = ref(""),
  notice = ref("");
const projectForm = reactive<{
  id: string;
  name: string;
  repositoryUrl: string;
  defaultBranch: string;
}>({ id: "", name: "", repositoryUrl: "", defaultBranch: "main" });
function message(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error
    ? cause.message
    : String(cause);
}
function clearFeedback() {
  error.value = "";
  notice.value = "";
}
async function loadProjects(preferred?: string) {
  busy.value = true;
  clearFeedback();
  try {
    projects.value = await projectApi.list();
    const id = preferred ?? selected.value?.id ?? projects.value[0]?.id;
    if (id) await selectProject(id);
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
async function selectProject(id: string) {
  clearFeedback();
  selected.value = await projectApi.get(id);
  Object.assign(projectForm, {
    id: selected.value.id,
    name: selected.value.name,
    repositoryUrl: selected.value.repositoryUrl ?? "",
    defaultBranch: selected.value.defaultBranch ?? "main",
  });
  pipelines.value = await projectApi.pipelines(id);
}
async function saved(action: () => Promise<unknown>, text: string) {
  busy.value = true;
  clearFeedback();
  try {
    await action();
    notice.value = text;
    await loadProjects(selected.value?.id);
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}
function newProject() {
  Object.assign(projectForm, {
    id: "",
    name: "",
    repositoryUrl: "",
    defaultBranch: "main",
  });
  selected.value = null;
  pipelines.value = [];
}
onMounted(() =>
  loadProjects(
    new URLSearchParams(window.location.search).get("project") ?? undefined,
  ),
);
</script>

<template>
  <section class="dashboard asset-layout">
    <aside class="panel asset-list">
      <div class="section-title">
        <h2>
          Projects <span class="count">{{ projects.length }}</span>
        </h2>
        <button class="ghost" @click="newProject">新建</button>
      </div>
      <button
        v-for="project in projects"
        :key="project.id"
        class="run-row"
        :class="{ active: selected?.id === project.id }"
        @click="selectProject(project.id)"
      >
        <strong>{{ project.name }}</strong>
      </button>
      <p v-if="!busy && !projects.length" class="empty">暂无被测项目。</p>
    </aside>
    <div>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <p v-if="notice" class="success" role="status">{{ notice }}</p>
      <article class="panel">
        <h2>{{ projectForm.id ? "被测项目配置" : "创建被测项目" }}</h2>
        <form
          class="form-grid"
          @submit.prevent="
            saved(
              () =>
                projectApi.save({
                  ...projectForm,
                  id: projectForm.id || undefined,
                }),
              '项目已保存',
            )
          "
        >
          <label
            >名称<input
              v-model.trim="projectForm.name"
              maxlength="200"
              required /></label
          ><label
            >Git 仓库<input
              v-model.trim="projectForm.repositoryUrl"
              maxlength="2048"
              required /></label
          ><label
            >默认分支<input
              v-model.trim="projectForm.defaultBranch"
              maxlength="255"
              required /></label
          ><button class="primary" :disabled="busy">保存 Project</button>
        </form>
      </article>
      <template v-if="selected">
        <article class="panel">
          <div class="section-title">
            <h2>CI Pipeline 目录</h2>
            <small
              >由开发/运维在 Jenkins 维护，TestForge 只读并按分支触发。</small
            >
          </div>
          <div class="asset-tabs pipeline-catalog" role="list">
            <div
              v-for="pipeline in pipelines"
              :key="pipeline.externalId"
              role="listitem"
              :class="{ active: pipeline.enabled }"
            >
              {{ pipeline.name }} · {{ pipeline.kind }} ·
              {{ pipeline.enabled ? "READY" : "DISABLED" }}
            </div>
          </div>
          <p v-if="!pipelines.length" class="empty">
            当前 Project 尚未绑定可用 Pipeline。
          </p>
        </article>
        <CaseAssetWorkbench :project="selected" />
      </template>
    </div>
  </section>
</template>
