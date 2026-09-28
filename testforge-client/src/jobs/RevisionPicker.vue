<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { projectApi, type RevisionOption, type RevisionType } from "../projects";

const props = withDefaults(defineProps<{ projectId: string; defaultBranch?: string | null; type: RevisionType; value: string; disabled?: boolean }>(), { disabled: false });
const emit = defineEmits<{ select: [value: { type: "BRANCH" | "TAG"; value: string; option: RevisionOption }] }>();
const open = ref(false), busy = ref(false), error = ref("");
const activeTab = ref<"BRANCH" | "TAG">("BRANCH");
const branches = ref<RevisionOption[]>([]), tags = ref<RevisionOption[]>([]);
const visibleOptions = computed(() => activeTab.value === "BRANCH" ? branches.value : tags.value);
const selected = computed(() => [...branches.value, ...tags.value]
  .find(item => item.type === props.type && item.name === props.value));

async function load(type: "BRANCH" | "TAG") {
  if (!props.projectId) return;
  const target = type === "BRANCH" ? branches : tags;
  if (target.value.length) return;
  busy.value = true; error.value = "";
  try {
    target.value = await projectApi.revisions(props.projectId, type);
    if (type === "BRANCH" && !selected.value) {
      const option = target.value.find(item => item.defaultBranch)
        ?? target.value.find(item => item.name === props.defaultBranch) ?? target.value[0];
      if (option) emit("select", { type: "BRANCH", value: option.name, option });
    }
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : String(cause);
  } finally { busy.value = false; }
}
async function showTab(type: "BRANCH" | "TAG") { activeTab.value = type; await load(type); }
function choose(option: RevisionOption) { emit("select", { type: option.type, value: option.name, option }); open.value = false; }

watch(() => props.projectId, async () => {
  branches.value = []; tags.value = []; activeTab.value = "BRANCH"; open.value = false;
  await load("BRANCH");
}, { immediate: true });
</script>

<template>
  <div class="revision-picker">
    <button type="button" class="revision-trigger" :disabled="disabled || !projectId || busy" @click="open = !open">
      <template v-if="selected">
        <span><b>{{ selected.type === 'BRANCH' ? '分支' : 'Tag' }}</b> {{ selected.name }}</span>
        <code>{{ selected.commitSha.slice(0, 10) }}</code><small>{{ selected.commitMessage }}</small>
      </template>
      <span v-else>{{ busy ? '正在读取 Git 版本…' : '选择分支或 Tag' }}</span><i aria-hidden="true">⌄</i>
    </button>
    <div v-if="open" class="revision-menu">
      <div class="revision-tabs" role="tablist" aria-label="Git 版本类型">
        <button type="button" :class="{ active: activeTab === 'BRANCH' }" @click="showTab('BRANCH')">分支</button>
        <button type="button" :class="{ active: activeTab === 'TAG' }" @click="showTab('TAG')">Tag</button>
      </div>
      <p v-if="error" class="error">{{ error }}</p>
      <div class="revision-options">
        <button v-for="option in visibleOptions" :key="`${option.type}-${option.name}`" type="button"
          :class="{ selected: option.type === type && option.name === value }" @click="choose(option)">
          <span><b>{{ option.name }}</b><em v-if="option.defaultBranch">默认</em></span>
          <code>{{ option.commitSha.slice(0, 10) }}</code><small>{{ option.commitMessage }}</small>
        </button>
        <p v-if="!busy && !visibleOptions.length" class="empty">没有可选择的{{ activeTab === 'BRANCH' ? '分支' : ' Tag' }}。</p>
      </div>
    </div>
  </div>
</template>
