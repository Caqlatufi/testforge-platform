package io.testforge.dispatcher.stream;

import io.testforge.dispatcher.port.outbound.RunnerType;
import io.testforge.dispatcher.port.outbound.TaskDispatchMessage;
import io.testforge.dispatcher.port.outbound.WorkerPlatform;
import io.testforge.runorchestrator.task.model.ResourceMode;

import java.util.Locale;
import java.util.Objects;

/**
 * 将不同执行器任务路由到互不争抢的 Redis Stream。
 */
public final class RedisStreamRouteResolver {

    public static final String DEFAULT_PREFIX = "testforge:tasks";

    private final String streamPrefix;

    public RedisStreamRouteResolver() {
        this(DEFAULT_PREFIX);
    }

    public RedisStreamRouteResolver(String streamPrefix) {
        if (streamPrefix == null || streamPrefix.isBlank() || streamPrefix.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("streamPrefix 不能为空或包含空白字符");
        }
        this.streamPrefix = stripTrailingColon(streamPrefix);
    }

    public String resolve(TaskDispatchMessage message) {
        Objects.requireNonNull(message, "message must not be null");
        return resolve(message.execution().resourceMode(), message.execution().runner(), message.execution().platform());
    }

    public String resolve(RunnerType runner, WorkerPlatform platform) {
        return resolve(
                runner == RunnerType.AIRTEST ? ResourceMode.EXCLUSIVE_DEVICE : ResourceMode.PROCESS_POOL,
                runner,
                platform
        );
    }

    public String resolve(ResourceMode resourceMode, RunnerType runner, WorkerPlatform platform) {
        Objects.requireNonNull(resourceMode, "resourceMode must not be null");
        Objects.requireNonNull(runner, "runner must not be null");
        Objects.requireNonNull(platform, "platform must not be null");
        if (resourceMode == ResourceMode.PROCESS_POOL && runner == RunnerType.PYTEST_HTTP) {
            return streamPrefix + ":pytest-http";
        }
        if (resourceMode == ResourceMode.PROCESS_POOL && runner == RunnerType.PLAYWRIGHT_WEB) {
            return streamPrefix + ":playwright-web";
        }

        if (resourceMode != ResourceMode.EXCLUSIVE_DEVICE || runner != RunnerType.AIRTEST) {
            throw new IllegalArgumentException("resourceMode 与 runner 不匹配");
        }

        if (platform == WorkerPlatform.ANY || platform == WorkerPlatform.LINUX) {
            throw new IllegalArgumentException("airtest 消息不能路由到平台 " + platform.contractValue());
        }
        return streamPrefix + ":airtest:" + platform.contractValue().toLowerCase(Locale.ROOT);
    }

    private static String stripTrailingColon(String prefix) {
        int end = prefix.length();
        while (end > 0 && prefix.charAt(end - 1) == ':') {
            end--;
        }
        if (end == 0) {
            throw new IllegalArgumentException("streamPrefix 不能只包含冒号");
        }
        return prefix.substring(0, end);
    }
}
