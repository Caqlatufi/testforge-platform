<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ApiError } from "../common";
import {
  environmentApi,
  type EnvironmentPlatform,
  type EnvironmentRuntimeStatus,
  type TestEnvironment,
} from ".";

const environments = ref<TestEnvironment[]>([]);
const runtimeStatuses = ref<Record<string, EnvironmentRuntimeStatus>>({});
const selected = ref<TestEnvironment | null>(null);
const platformFilter = ref<EnvironmentPlatform | "ALL">("ALL");
const busy = ref(false);
const error = ref("");
const notice = ref("");
const form = reactive({
  id: "",
  name: "",
  platform: "WINDOWS" as EnvironmentPlatform,
  resourcePoolKey: "windows",
  providerEnvironmentKey: "",
  endpoint: "",
  capacity: 1,
  enabled: true,
  initializeOnNextDeploy: true,
  config: "{}",
  secretRefs: "{}",
});

const visibleEnvironments = computed(() =>
  platformFilter.value === "ALL"
    ? environments.value
    : environments.value.filter((item) => item.platform === platformFilter.value),
);
const selectedRuntime = computed(() => selected.value ? runtimeStatuses.value[selected.value.id] : undefined);

function message(cause: unknown) {
  return cause instanceof ApiError || cause instanceof Error
    ? cause.message
    : String(cause);
}

function parseObject(value: string, label: string) {
  const parsed = JSON.parse(value) as unknown;
  if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
    throw new Error(`${label} 必须是 JSON 对象`);
  }
  return parsed as Record<string, unknown>;
}

function parseSecretRefs(value: string) {
  const parsed = parseObject(value, "Secret refs");
  if (Object.values(parsed).some((item) => typeof item !== "string")) {
    throw new Error("Secret refs 的值必须是字符串引用");
  }
  return parsed as Record<string, string>;
}

function configValue(item: TestEnvironment, key: string) {
  const value = item.config[key];
  return typeof value === "string" ? value : "未绑定";
}

function runtime(item: TestEnvironment) {
  return runtimeStatuses.value[item.id];
}

function runtimeLabel(item: TestEnvironment) {
  const state = runtime(item)?.state;
  if (state === "RUNNING") return "RUNNING";
  if (state === "SAVED") return "SAVED";
  if (state === "STOPPED") return "STOPPED";
  if (state === "PAUSED") return "PAUSED";
  if (state === "ERROR") return "UNAVAILABLE";
  return item.enabled ? "REGISTERED" : "DISABLED";
}

function runtimeClass(item: TestEnvironment) {
  return runtime(item)?.state === "RUNNING" ? "status-succeeded" : "status-disabled";
}

function edit(item?: TestEnvironment) {
  selected.value = item ?? null;
  Object.assign(
    form,
    item
      ? {
          id: item.id,
          name: item.name,
          platform: item.platform,
          resourcePoolKey: item.resourcePoolKey,
          providerEnvironmentKey: item.providerEnvironmentKey,
          endpoint: item.endpoint ?? "",
          capacity: 1,
          enabled: item.enabled,
          initializeOnNextDeploy: item.initializeOnNextDeploy,
          config: JSON.stringify(item.config, null, 2),
          secretRefs: JSON.stringify(item.secretRefs, null, 2),
        }
      : {
          id: "",
          name: "",
          platform: "WINDOWS",
          resourcePoolKey: "windows",
          providerEnvironmentKey: "",
          endpoint: "",
          capacity: 1,
          enabled: true,
          initializeOnNextDeploy: true,
          config: "{}",
          secretRefs: "{}",
        },
  );
}

function platformChanged() {
  if (!form.id) form.resourcePoolKey = form.platform.toLowerCase();
}

async function load() {
  busy.value = true;
  error.value = "";
  try {
    const [items, statuses] = await Promise.all([
      environmentApi.list(),
      environmentApi.runtimeStatuses(),
    ]);
    environments.value = items;
    runtimeStatuses.value = Object.fromEntries(statuses.map((item) => [item.environmentId, item]));
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}

async function refreshRuntime() {
  busy.value = true;
  error.value = "";
  try {
    const statuses = await environmentApi.runtimeStatuses();
    runtimeStatuses.value = Object.fromEntries(statuses.map((item) => [item.environmentId, item]));
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}

async function startEnvironment() {
  if (!selected.value) return;
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const status = await environmentApi.start(selected.value.id);
    runtimeStatuses.value = { ...runtimeStatuses.value, [status.environmentId]: status };
    if (status.state === "ERROR") error.value = status.message;
    else notice.value = `${selected.value.name} 已启动`;
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}

async function save() {
  busy.value = true;
  error.value = "";
  notice.value = "";
  try {
    const saved = await environmentApi.save({
      id: form.id || undefined,
      name: form.name,
      platform: form.platform,
      resourcePoolKey: form.resourcePoolKey,
      providerEnvironmentKey: form.providerEnvironmentKey,
      endpoint: form.endpoint || null,
      capacity: 1,
      enabled: form.enabled,
      initializeOnNextDeploy: form.initializeOnNextDeploy,
      config: parseObject(form.config, "环境配置"),
      secretRefs: parseSecretRefs(form.secretRefs),
    });
    notice.value = "环境实例已保存";
    await load();
    edit(environments.value.find((item) => item.id === saved.id));
  } catch (cause) {
    error.value = message(cause);
  } finally {
    busy.value = false;
  }
}

onMounted(load);
</script>

<template>
  <p v-if="error" class="error" role="alert">{{ error }}</p>
  <p v-if="notice" class="success">{{ notice }}</p>
  <section class="dashboard asset-layout">
    <aside class="panel asset-list">
      <div class="section-title">
        <h2>ENVIRONMENTS <span class="count">{{ environments.length }}</span></h2>
        <button class="ghost" @click="edit()">登记</button>
      </div>
      <label>
        平台筛选
        <select v-model="platformFilter">
          <option value="ALL">全部平台</option>
          <option value="WINDOWS">Windows</option>
          <option value="ANDROID">Android</option>
          <option value="IOS">iOS</option>
        </select>
      </label>
      <button
        v-for="environment in visibleEnvironments"
        :key="environment.id"
        class="run-row"
        :class="{ active: selected?.id === environment.id }"
        @click="edit(environment)"
      >
        <strong>{{ environment.name }}</strong>
        <span :class="runtimeClass(environment)">
          {{ runtimeLabel(environment) }}
        </span>
        <small>{{ environment.platform }} · {{ environment.resourcePoolKey }}</small>
        <small>{{ configValue(environment, "workerId") }} · {{ configValue(environment, "deviceId") }}</small>
      </button>
      <p v-if="!visibleEnvironments.length" class="empty">当前筛选没有共享环境。</p>
    </aside>

    <article class="panel">
      <div class="section-title">
        <h2>{{ form.id ? "编辑环境实例" : "登记环境实例" }}</h2>
        <div class="environment-runtime-actions">
          <span class="connection">{{ selectedRuntime?.state ?? "GLOBAL RESOURCE" }}</span>
          <button v-if="selectedRuntime?.controllable && selectedRuntime.state !== 'RUNNING'" class="ghost" type="button" :disabled="busy" @click="startEnvironment">{{ busy ? "启动中…" : "启动环境" }}</button>
          <button v-if="form.id" class="ghost" type="button" :disabled="busy" @click="refreshRuntime">刷新状态</button>
        </div>
      </div>
      <p class="empty">
        一台 VM、模拟器或设备对应一个环境实例。Test Job 只选择系统类型，平台在执行时自动分配测试机。
      </p>
      <p v-if="selectedRuntime" class="environment-runtime-summary">运行状态：<strong>{{ selectedRuntime.state }}</strong> · {{ selectedRuntime.message }}</p>
      <form class="form-grid" @submit.prevent="save">
        <label>环境名称<input v-model.trim="form.name" maxlength="200" required /></label>
        <label>
          平台
          <select v-model="form.platform" required @change="platformChanged">
            <option value="WINDOWS">Windows</option>
            <option value="ANDROID">Android</option>
            <option value="IOS">iOS</option>
          </select>
        </label>
        <label>
          资源池键
          <input v-model.trim="form.resourcePoolKey" pattern="[A-Za-z0-9][A-Za-z0-9._/-]{0,127}" required />
        </label>
        <label>
          Jenkins 发布键
          <input v-model.trim="form.providerEnvironmentKey" pattern="[A-Za-z0-9][A-Za-z0-9._/-]{0,254}" required />
        </label>
        <label>Endpoint（发布成功后可回填）<input v-model.trim="form.endpoint" /></label>
        <label>
          实例容量
          <input value="1" type="number" readonly />
        </label>
        <label class="check"><input v-model="form.enabled" type="checkbox" />允许新任务选择</label>
        <label class="check">
          <input v-model="form.initializeOnNextDeploy" type="checkbox" />下次发布前初始化环境
        </label>
        <label>Config JSON<textarea v-model="form.config" required /></label>
        <label>Secret refs JSON<textarea v-model="form.secretRefs" required /></label>
        <button class="primary" :disabled="busy">保存环境实例</button>
      </form>
    </article>
  </section>
</template>
