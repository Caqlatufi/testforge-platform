package io.testforge.dispatcher.stream;

import io.testforge.dispatcher.port.outbound.WorkerPlatform;
import io.testforge.dispatcher.port.outbound.RunnerType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisStreamRouteResolverTest {

    private final RedisStreamRouteResolver resolver = new RedisStreamRouteResolver();

    @Test
    void routesPytestToSharedRunnerStream() {
        assertThat(resolver.resolve(TaskDispatchMessageFixtures.pytestMessage()))
                .isEqualTo("testforge:tasks:pytest-http");
    }

    @Test
    void routesAirtestByRunnerAndConcretePlatform() {
        assertThat(resolver.resolve(TaskDispatchMessageFixtures.airtestMessage(WorkerPlatform.WINDOWS)))
                .isEqualTo("testforge:tasks:airtest:windows");
        assertThat(resolver.resolve(TaskDispatchMessageFixtures.airtestMessage(WorkerPlatform.ANDROID)))
                .isEqualTo("testforge:tasks:airtest:android");
        assertThat(resolver.resolve(TaskDispatchMessageFixtures.airtestMessage(WorkerPlatform.IOS)))
                .isEqualTo("testforge:tasks:airtest:ios");
    }

    @Test
    void routesPlaywrightWebToDedicatedRunnerStream() {
        assertThat(resolver.resolve(RunnerType.PLAYWRIGHT_WEB, WorkerPlatform.WINDOWS))
                .isEqualTo("testforge:tasks:playwright-web");
    }

    @Test
    void normalizesConfigurablePrefix() {
        var custom = new RedisStreamRouteResolver("demo:dispatch::");

        assertThat(custom.resolve(TaskDispatchMessageFixtures.pytestMessage()))
                .isEqualTo("demo:dispatch:pytest-http");
    }
}
