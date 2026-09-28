import type { FeatureModule } from "../common";
import { api } from "../common/api";

export const projectsFeature: FeatureModule = {
  id: "projects",
  title: "被测项目",
  description: "管理被测应用、Git 仓库与 CI Pipeline 目录。",
};

export type TargetType = "HTTP_SERVICE" | "WEB" | "DESKTOP" | "MOBILE";
export type RevisionType = "DEFAULT_BRANCH" | "BRANCH" | "TAG" | "COMMIT";
export interface Environment {
  id: string;
  targetId: string;
  name: string;
  endpoint?: string | null;
  providerEnvironmentKey: string;
  platform: "WINDOWS" | "ANDROID" | "IOS";
  resourcePoolKey: string;
  capacity: number;
  enabled: boolean;
  initializeOnNextDeploy: boolean;
  config: Record<string, unknown>;
  secretRefs: Record<string, string>;
}
export interface Target {
  id: string;
  projectId: string;
  name: string;
  type: TargetType;
  repositoryUrl?: string | null;
  defaultBranch?: string | null;
  environments: Environment[];
}
export interface Project {
  id: string;
  name: string;
  code: string;
  state: "ACTIVE" | "ARCHIVED";
  targetType: TargetType;
  repositoryUrl?: string | null;
  defaultBranch?: string | null;
  ciProvider?: string | null;
  ciServerUrl?: string | null;
  ciFolder?: string | null;
  ciCredentialRef?: string | null;
  targets: Target[];
}
export interface ResolvedRevision {
  requestedType: RevisionType;
  requestedValue?: string | null;
  commitSha: string;
  resolvedAt: string;
}
export interface RevisionOption {
  type: "BRANCH" | "TAG";
  name: string;
  commitSha: string;
  commitMessage: string;
  committedAt: string;
  defaultBranch: boolean;
}
export interface PipelineRef {
  externalId: string;
  name: string;
  provider: string;
  serverUrl: string;
  jobName: string;
  revision: string;
  enabled: boolean;
  kind: "MULTIBRANCH" | "PIPELINE";
}
export interface PipelineEnvironmentRef {
  externalId: string;
  name: string;
  providerKey: string;
  platform: "WINDOWS" | "ANDROID" | "IOS";
  resourcePoolKey: string;
  capacity: number;
}

export const projectApi = {
  list: () => api<Project[]>("/api/v1/projects"),
  get: (id: string) => api<Project>(`/api/v1/projects/${id}`),
  save: (value: {
    id?: string;
    name: string;
    repositoryUrl: string;
    defaultBranch: string;
  }) =>
    api<Project>(
      value.id ? `/api/v1/projects/${value.id}` : "/api/v1/projects",
      {
        method: value.id ? "PUT" : "POST",
        body: JSON.stringify({
          name: value.name,
          repositoryUrl: value.repositoryUrl || null,
          defaultBranch: value.defaultBranch || null,
        }),
      },
    ),
  resolveRevision: (projectId: string, type: RevisionType, value?: string) =>
    api<ResolvedRevision>(`/api/v1/projects/${projectId}/revisions/resolve`, {
      method: "POST",
      body: JSON.stringify({ type, value: value || null }),
    }),
  revisions: (projectId: string, type: "BRANCH" | "TAG") =>
    api<RevisionOption[]>(`/api/v1/projects/${projectId}/revisions?type=${type}`),
  pipelines: (projectId: string) =>
    api<PipelineRef[]>(`/api/v1/projects/${projectId}/pipelines`),
};
