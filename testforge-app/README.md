# testforge-app

TestForge 的 Java 21 / Spring Boot Gradle 多模块控制面。

- `app` 是唯一可执行模块，也是唯一生成 `bootJar` 的模块。
- `common` 只提供共享基础能力。
- 十个业务模块按领域边界协作，通过公开 Service、Port、Model 或 Event 交互，不跨模块访问 Repo。
- MySQL 是状态真相源，Redis 只承担派发、租约辅助和短期实时数据。

增量数据迁移使用 `yhc-framework/simple-migration:1.0.1`。首次在新机器构建前，
需在 yhc-framework 根目录执行：

```powershell
.\gradlew.bat :commons:publishToMavenLocal :simple-migration:publishToMavenLocal
```

业务表和普通字段由实体及 `spring.jpa.hibernate.ddl-auto=update` 自动初始化与补齐。
新的兼容、索引和数据修正 SQL 放入所属模块的 `src/main/resources/simple-migration`。

```powershell
.\gradlew.bat projects
.\gradlew.bat verifyModuleBoundaries
.\gradlew.bat clean build
.\gradlew.bat :app:bootRun
```

`verifyModuleBoundaries` 会校验十二个子模块、文档约定的项目依赖矩阵、无循环依赖，以及只有 `app` 生成 `bootJar`。Gradle 在本机没有 Java 21 时通过 Foojay 工具链解析器自动供应匹配 JDK。

应用启动后，公开健康检查为 `GET /health`，Actuator 原生检查仍保留在 `GET /actuator/health`。

开发、测试和演示的外置配置入口分别位于 `config/dev`、`config/test` 与 `config/demo`。真实凭据不得提交。
