package io.testforge.casecatalog.workflow.compile.model;

import java.util.UUID;

public record ReferenceKey(UUID id, int version) {

    public ReferenceKey {
        if (id == null) {
            throw new IllegalArgumentException("引用 ID 不能为空");
        }
        if (version < 1) {
            throw new IllegalArgumentException("引用版本必须大于 0");
        }
    }
}
