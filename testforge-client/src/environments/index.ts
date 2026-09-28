import type { FeatureModule } from "../common";
import { api } from "../common/api";

export const environmentsFeature: FeatureModule = {
  id: "environments",
  title: "环境资源",
  description: "统一维护跨项目复用的虚拟机、模拟器与设备实例；资源池由平台内部聚合。",
};

export type EnvironmentPlatform = "WINDOWS" | "ANDROID" | "IOS";

export interface TestEnvironment {
  id: string;
  targetId?: string | null;
  name: string;
  endpoint?: string | null;
  providerEnvironmentKey: string;
  platform: EnvironmentPlatform;
  resourcePoolKey: string;
  capacity: number;
  enabled: boolean;
  initializeOnNextDeploy: boolean;
  config: Record<string, unknown>;
  secretRefs: Record<string, string>;
}

export interface EnvironmentSave {
  id?: string;
  name: string;
  endpoint?: string | null;
  providerEnvironmentKey: string;
  platform: EnvironmentPlatform;
  resourcePoolKey: string;
  capacity: number;
  enabled: boolean;
  initializeOnNextDeploy: boolean;
  config: Record<string, unknown>;
  secretRefs: Record<string, string>;
}

export interface EnvironmentRuntimeStatus {
  environmentId: string;
  provider: "HYPER_V" | "EXTERNAL";
  state: "RUNNING" | "STOPPED" | "SAVED" | "PAUSED" | "UNMANAGED" | "UNKNOWN" | "ERROR";
  controllable: boolean;
  message: string;
}

export const environmentApi = {
  list: (enabled?: boolean, platform?: EnvironmentPlatform) => {
    const query = new URLSearchParams();
    if (enabled !== undefined) query.set("enabled", String(enabled));
    if (platform) query.set("platform", platform);
    const serialized = query.toString();
    const suffix = serialized ? `?${serialized}` : "";
    return api<TestEnvironment[]>(`/api/v1/environments${suffix}`);
  },
  save: (value: EnvironmentSave) =>
    api<TestEnvironment>(
      value.id ? `/api/v1/environments/${value.id}` : "/api/v1/environments",
      {
        method: value.id ? "PUT" : "POST",
        body: JSON.stringify(value),
      },
    ),
  runtimeStatuses: () => api<EnvironmentRuntimeStatus[]>("/api/v1/environments/runtime"),
  start: (environmentId: string) => api<EnvironmentRuntimeStatus>(`/api/v1/environments/runtime/${environmentId}/start`, {
    method: "POST",
  }),
};
