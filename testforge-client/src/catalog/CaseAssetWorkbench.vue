<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ApiError } from "../common/api";
import type { Project } from "../projects";
import { catalogApi, type CaseAsset, type TestCase } from "./index";

const props = defineProps<{ project: Project }>();
const cases = ref<TestCase[]>([]);
const assets = ref<CaseAsset[]>([]);
const selectedCaseId = ref("");
const definitionYaml = ref("");
const definitionDigest = ref("");
const boundAssetId = ref("");
const busy = ref(false);
const uploading = ref(false);
const error = ref("");
const notice = ref("");
const fileInput = ref<HTMLInputElement>();

const projectCases = computed(() => cases.value.filter((item) => item.scope !== "SHARED"));
const currentCase = computed(() => cases.value.find((item) => item.id === selectedCaseId.value));
const editing = computed(() => Boolean(selectedCaseId.value));
const boundAsset = computed(() => assets.value.find((item) => item.id === boundAssetId.value));

function message(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : String(cause);
}
function clearFeedback() { error.value = ""; notice.value = ""; }

async function bindAsset(assetId: string | null) {
  boundAssetId.value = assetId ?? "";
  if (!assetId || assets.value.some((item) => item.id === assetId)) return;
  const asset = await catalogApi.asset(assetId);
  assets.value.unshift(asset);
}

function defaultYaml(name = "新建 Case") {
  return `apiVersion: testforge.io/v1alpha1
kind: TestCase
metadata:
  name: ${JSON.stringify(name)}
  tags: []
spec:
  type: assertion
  timeoutSeconds: 30
  execution:
    executor: pytest-http
    interaction: HEADLESS
    capabilities: []
    resourceProfile: script-small
    leaseScope: CASE
  parameters:
    type: object
    additionalProperties: true
  script:
    type: inline
    language: python
    entrypoint: case_test.py
    content: |
      def test_case():
          assert True
`;
}

function legacyYaml(item: TestCase) {
  if (item.executionRequirement.executorType !== "airtest") {
    return defaultYaml(item.name).replace("tags: []", `tags: ${JSON.stringify(item.tags)}`);
  }
  return `apiVersion: testforge.io/v1alpha1
kind: TestCase
metadata:
  name: ${JSON.stringify(item.name)}
  tags: ${JSON.stringify(item.tags)}
spec:
  type: ${item.kind.toLowerCase()}
  timeoutSeconds: ${item.timeoutSeconds}
  execution:
    executor: airtest
    interaction: UI
    capabilities: ["WINDOWS_UI"]
    resourceProfile: ui-default
    leaseScope: CASE
  parameters:
    type: object
    additionalProperties: true
  script:
    type: asset
    asset: testforge://assets/REPLACE_WITH_UPLOADED_ASSET_ID
    entrypoint: REPLACE_WITH_CASE_ENTRYPOINT.air
`;
}

function newCase() {
  clearFeedback();
  selectedCaseId.value = "";
  definitionDigest.value = "";
  boundAssetId.value = "";
  definitionYaml.value = defaultYaml();
}

async function selectCase(item: TestCase) {
  clearFeedback();
  selectedCaseId.value = item.id;
  if (!item.yamlManaged) {
    boundAssetId.value = "";
    definitionYaml.value = legacyYaml(item);
    definitionDigest.value = "";
    notice.value = "这是旧版 Case。请为它选择专属脚本资源；保存后会转换为 YAML-first Case，旧脚本版本仅保留用于历史快照。";
    return;
  }
  busy.value = true;
  try {
    const view = await catalogApi.definition(item.id);
    definitionYaml.value = view.yaml;
    definitionDigest.value = view.digest;
    await bindAsset(view.assetId);
  } catch (cause) { error.value = message(cause); }
  finally { busy.value = false; }
}

async function load(projectId: string) {
  busy.value = true;
  clearFeedback();
  try {
    cases.value = await catalogApi.cases(projectId);
    try {
      assets.value = await catalogApi.assets(projectId);
    } catch (cause) {
      assets.value = [];
      if (!(cause instanceof ApiError && cause.status === 404)) throw cause;
      notice.value = "脚本资源服务尚未加载；现有 Case 仍可浏览，重启后端后即可上传资源。";
    }
    const first = projectCases.value[0];
    if (first) await selectCase(first); else newCase();
  } catch (cause) { error.value = message(cause); }
  finally { busy.value = false; }
}

async function validateYaml() {
  busy.value = true;
  clearFeedback();
  try {
    const view = await catalogApi.validateDefinition(props.project.id, definitionYaml.value);
    notice.value = `YAML 校验通过：${view.name} · ${view.executionRequirement.executorType}`;
  } catch (cause) { error.value = message(cause); }
  finally { busy.value = false; }
}

async function saveYaml() {
  busy.value = true;
  clearFeedback();
  try {
    const wasEditing = editing.value;
    const saved = wasEditing
      ? await catalogApi.updateDefinition(selectedCaseId.value, definitionYaml.value)
      : await catalogApi.createDefinition(props.project.id, definitionYaml.value);
    const view = await catalogApi.definition(saved.id);
    const index = cases.value.findIndex((item) => item.id === saved.id);
    if (index >= 0) cases.value[index] = saved; else cases.value.unshift(saved);
    selectedCaseId.value = saved.id;
    definitionYaml.value = view.yaml;
    definitionDigest.value = view.digest;
    await bindAsset(view.assetId);
    notice.value = wasEditing ? "Case YAML 已更新" : "Case YAML 已创建";
  } catch (cause) { error.value = message(cause); }
  finally { busy.value = false; }
}

async function upload(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  uploading.value = true;
  clearFeedback();
  try {
    const asset = await catalogApi.uploadAsset(props.project.id, file);
    if (!assets.value.some((item) => item.id === asset.id)) assets.value.unshift(asset);
    boundAssetId.value = asset.id;
    const entrypoint = candidateEntrypoints(asset)[0];
    if (!entrypoint) throw new Error(`${asset.fileName} 没有唯一可执行入口`);
    insertAsset(asset, entrypoint);
    notice.value = `${asset.fileName} 已绑定到当前 Case 并写入 YAML；保存 Case 后影响后续执行`;
  } catch (cause) { error.value = message(cause); }
  finally { uploading.value = false; input.value = ""; }
}

function candidateEntrypoints(asset: CaseAsset) {
  if (asset.entrypoints?.length) return asset.entrypoints;
  return /\.(?:py|air)$/i.test(asset.fileName) ? [asset.fileName] : [];
}

function insertAsset(asset: CaseAsset, selected?: string) {
  clearFeedback();
  const entrypoint = selected || candidateEntrypoints(asset)[0];
  if (!entrypoint) {
    error.value = `${asset.fileName} 没有可执行入口；ZIP 需要包含 .air 目录或 Python 文件。`;
    return;
  }
  boundAssetId.value = asset.id;
  const block = `  script:\n    type: asset\n    asset: ${asset.uri}\n    entrypoint: ${JSON.stringify(entrypoint)}\n`;
  const scriptBlock = /^  script:\n(?: {4}.*\n?)*/m;
  definitionYaml.value = scriptBlock.test(definitionYaml.value)
    ? definitionYaml.value.replace(scriptBlock, block)
    : `${definitionYaml.value.trimEnd()}\n${block}`;
  notice.value = `${asset.fileName} · ${entrypoint} 已插入 Case YAML，保存后生效`;
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / 1024 / 1024).toFixed(1)} MiB`;
}

watch(() => props.project.id, (projectId) => void load(projectId), { immediate: true });
</script>

<template>
  <article class="panel case-assets-panel yaml-first-workbench">
    <header class="case-assets-heading">
      <div><small class="eyebrow">YAML-FIRST TEST ASSETS</small><h2>Case 定义与脚本资源</h2><p>YAML 是 Case 的唯一可编辑源；脚本上传到 TestForge，并通过资源 URI 写入定义。</p></div>
      <button class="ghost" type="button" @click="newCase">＋ 新建 Case</button>
    </header>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-if="notice" class="success" role="status">{{ notice }}</p>

    <div class="yaml-case-layout">
      <aside class="case-catalog-panel">
        <div class="case-catalog-title"><strong>项目 Case</strong><span class="count">{{ projectCases.length }}</span></div>
        <div class="case-catalog-scroll">
          <button v-for="item in projectCases" :key="item.id" type="button" class="case-catalog-item" :class="{ active: selectedCaseId === item.id }" @click="selectCase(item)">
            <strong>{{ item.name }}</strong><span>{{ item.executionRequirement?.executorType }}</span>
            <small>{{ item.yamlManaged ? `YAML · ${item.definitionDigest?.slice(0, 12)}` : "LEGACY · 待转换" }}</small>
          </button>
          <p v-if="!busy && !projectCases.length" class="empty">当前项目还没有 Case。</p>
        </div>
      </aside>

      <section class="yaml-editor-panel">
        <header><div><small>{{ editing ? "EDIT CASE" : "NEW CASE" }}</small><h3>{{ currentCase?.name ?? "定义一个原子测试" }}</h3></div><span v-if="definitionDigest" class="case-project-badge">{{ definitionDigest.startsWith('sha256:') ? definitionDigest.slice(0, 19) : `sha256:${definitionDigest.slice(0, 12)}` }}</span></header>
        <textarea v-model="definitionYaml" class="case-yaml-editor" spellcheck="false" aria-label="Case YAML" />
        <footer><small>保存后，引用此 Case 的 Workflow 后续执行会自动使用新定义；已排队、运行中和历史执行继续使用各自快照。</small><div class="case-editor-actions"><button class="ghost" type="button" :disabled="busy" @click="validateYaml">校验 YAML</button><button class="primary" type="button" :disabled="busy" @click="saveYaml">{{ busy ? "处理中…" : editing ? "保存 Case" : "创建 Case" }}</button></div></footer>
      </section>

      <aside class="case-asset-panel">
        <header><div><small class="eyebrow">CASE BOUND</small><h3>当前 Case 脚本</h3></div><button class="ghost compact" type="button" :disabled="uploading" @click="fileInput?.click()">{{ uploading ? "上传中…" : boundAsset ? "替换" : "上传" }}</button><input ref="fileInput" hidden type="file" accept=".py,.air,.zip" @change="upload" /></header>
        <p class="asset-help">一个 Case 恰好绑定一个主脚本入口；ZIP 可携带辅助文件，但只能包含一个主入口。上传即替换当前 YAML 草稿绑定，保存后影响该 Case 的后续执行。</p>
        <div v-if="boundAsset" class="case-asset-list">
          <div class="case-asset-item active">
            <div class="case-asset-insert"><strong>{{ boundAsset.fileName }}</strong><span>{{ formatBytes(boundAsset.sizeBytes) }}</span><small>{{ boundAsset.sha256.slice(0, 18) }}…</small></div>
            <small v-if="candidateEntrypoints(boundAsset).length === 1">入口 · {{ candidateEntrypoints(boundAsset)[0] }}</small>
            <small v-else class="error">未识别到执行入口，请重新上传有效脚本包</small>
          </div>
        </div>
        <p v-else class="empty">当前 Case 尚未绑定脚本。上传后会写入此 Case 的 YAML 草稿。</p>
      </aside>
    </div>
  </article>
</template>
