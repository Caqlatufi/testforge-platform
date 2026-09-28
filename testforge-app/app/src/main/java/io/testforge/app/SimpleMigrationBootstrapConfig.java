package io.testforge.app;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import yhc.framework.migration.MigrationConfig;

/**
 * TestForge 的增量迁移入口。业务表的首次创建和字段补齐由 JPA DDL 管理，
 * 无法由实体表达的兼容、索引和数据迁移由 yhc-framework/simple-migration 执行。
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "simple.migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
@Import(MigrationConfig.class)
public class SimpleMigrationBootstrapConfig {
}
