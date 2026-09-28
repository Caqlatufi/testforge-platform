package io.testforge.app.environment;

import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class EnvironmentRuntimeService {
    private final ProjectCatalogService catalog;
    private final HyperVCommandExecutor hyperV;

    EnvironmentRuntimeService(ProjectCatalogService catalog, HyperVCommandExecutor hyperV) {
        this.catalog = catalog;
        this.hyperV = hyperV;
    }

    public List<EnvironmentRuntimeStatus> list() {
        return catalog.listEnvironments(null, null).stream().map(this::status).toList();
    }

    public EnvironmentRuntimeStatus start(UUID environmentId) {
        EnvironmentView environment = catalog.requireEnvironmentView(environmentId);
        String vmName = vmName(environment);
        if (vmName == null) return unmanaged(environment, "环境没有配置 vmName，生命周期由外部基础设施管理");
        if (!hyperV.supported()) return unavailable(environment, "当前控制面不支持 Hyper-V");
        try {
            return managed(environment, normalize(hyperV.start(vmName)), "虚拟机启动命令已完成");
        } catch (RuntimeException exception) {
            return unavailable(environment, safeMessage(exception));
        }
    }

    private EnvironmentRuntimeStatus status(EnvironmentView environment) {
        String vmName = vmName(environment);
        if (vmName == null) return unmanaged(environment, "生命周期由外部基础设施管理");
        if (!hyperV.supported()) return unavailable(environment, "当前控制面不支持 Hyper-V");
        try {
            return managed(environment, normalize(hyperV.state(vmName)), "Hyper-V · " + vmName);
        } catch (RuntimeException exception) {
            return unavailable(environment, safeMessage(exception));
        }
    }

    private String vmName(EnvironmentView environment) {
        if (environment.platform() != EnvironmentPlatform.WINDOWS) return null;
        Object value = environment.config().get("vmName");
        if (!(value instanceof String name) || name.isBlank()) return null;
        return name.trim();
    }

    private EnvironmentRuntimeStatus managed(EnvironmentView environment, String state, String message) {
        return new EnvironmentRuntimeStatus(environment.id(), "HYPER_V", state, true, message);
    }

    private EnvironmentRuntimeStatus unmanaged(EnvironmentView environment, String message) {
        return new EnvironmentRuntimeStatus(environment.id(), "EXTERNAL", "UNMANAGED", false, message);
    }

    private EnvironmentRuntimeStatus unavailable(EnvironmentView environment, String message) {
        return new EnvironmentRuntimeStatus(environment.id(), "HYPER_V", "ERROR", false, message);
    }

    private String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "RUNNING" -> "RUNNING";
            case "OFF" -> "STOPPED";
            case "SAVED" -> "SAVED";
            case "PAUSED" -> "PAUSED";
            default -> value.isBlank() ? "UNKNOWN" : value;
        };
    }

    private String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Hyper-V 操作失败" : message;
    }
}
