package io.testforge.dispatcher;

import io.testforge.dispatcher.adapter.inbound.DispatcherInboundAdapterMarker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DispatcherConfigTest {

    @Test
    void declaresTheCompletePersistenceModuleBoundary() {
        assertThat(DispatcherConfig.class).hasAnnotation(Configuration.class);
        assertThat(DispatcherConfig.class).hasAnnotation(ComponentScan.class);
        assertThat(DispatcherConfig.class).hasAnnotation(EntityScan.class);
        assertThat(DispatcherConfig.class).hasAnnotation(EnableJpaRepositories.class);
    }

    @Test
    void scansOnlyFromTheDispatcherModuleRoot() {
        var componentScan = DispatcherConfig.class.getAnnotation(ComponentScan.class);

        assertNotNull(componentScan);
        assertArrayEquals(
                new Class<?>[]{DispatcherInboundAdapterMarker.class},
                componentScan.basePackageClasses()
        );
    }
}
