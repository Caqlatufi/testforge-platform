package io.testforge.app.environment;

import io.testforge.projectcatalog.model.EnvironmentPlatform;
import io.testforge.projectcatalog.model.EnvironmentView;
import io.testforge.projectcatalog.service.ProjectCatalogService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EnvironmentRuntimeServiceTest {
    private final ProjectCatalogService catalog = mock(ProjectCatalogService.class);
    private final HyperVCommandExecutor hyperV = mock(HyperVCommandExecutor.class);
    private final EnvironmentRuntimeService service = new EnvironmentRuntimeService(catalog, hyperV);

    @Test
    void reportsSavedHyperVEnvironmentAndStartsOnlyItsConfiguredVm() {
        UUID id = UUID.randomUUID();
        EnvironmentView environment = environment(id, Map.of("vmName", "TF-WIN-A"));
        when(catalog.listEnvironments(null, null)).thenReturn(List.of(environment));
        when(catalog.requireEnvironmentView(id)).thenReturn(environment);
        when(hyperV.supported()).thenReturn(true);
        when(hyperV.state("TF-WIN-A")).thenReturn("Saved");
        when(hyperV.start("TF-WIN-A")).thenReturn("Running");

        assertThat(service.list().getFirst().state()).isEqualTo("SAVED");
        assertThat(service.start(id).state()).isEqualTo("RUNNING");
        verify(hyperV).start("TF-WIN-A");
    }

    @Test
    void leavesEnvironmentWithoutVmNameExternallyManaged() {
        UUID id = UUID.randomUUID();
        when(catalog.listEnvironments(null, null)).thenReturn(List.of(environment(id, Map.of())));

        EnvironmentRuntimeStatus status = service.list().getFirst();

        assertThat(status.state()).isEqualTo("UNMANAGED");
        assertThat(status.controllable()).isFalse();
    }

    private EnvironmentView environment(UUID id, Map<String, Object> config) {
        return new EnvironmentView(
                id, null, "Windows VM", "vm://TF-WIN-A", "tf-win-a",
                EnvironmentPlatform.WINDOWS, "windows-vm", 1, true, false,
                config, Map.of()
        );
    }
}
