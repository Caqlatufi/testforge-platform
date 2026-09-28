package io.testforge.runorchestrator.run.model;

import java.util.UUID;

public record CancelRunCommand(UUID requestKey, String reason) {
}
