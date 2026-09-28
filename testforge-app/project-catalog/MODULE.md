# project-catalog

拥有 TestProject、TestTarget 与 TestEnvironment，负责项目、被测对象和执行环境边界。

仅依赖 `common`；其他模块只能通过本模块公开的查询 Service 使用资产信息，不能访问 Repo。

## 包边界

| 包 | 职责 | 依赖约束 |
| --- | --- | --- |
| `ctrl` | 本模块 HTTP 入口 | 只调用公开 Service，不访问 Repo 或 Entity |
| `service` | 应用服务、领域规则和对外查询能力 | 本模块内唯一允许访问 Repo 的业务层 |
| `repo` | Project、Target、Environment 持久化端口 | 只允许被 Service 和模块配置访问 |
| `entity` | 本模块持久化实体 | 不暴露给其他模块 |
| `model` | DTO、枚举、值对象和查询模型 | 作为公开 Service 的稳定交换模型 |
| `event` | 本模块发布或消费的领域事件 | 不承载同步业务调用 |

`ProjectCatalogConfig` 是模块装配入口。`app` 依赖本模块并导入该配置类；配置类仅扫描
`ctrl`，通过 `@EntityScan`、`@EnableJpaRepositories` 和显式 `@Bean` 装配持久化与 Service。

## 资产约束

- Project `code` 全局唯一，创建后默认处于 `ACTIVE`。
- 同一 Project 内 Target 名称唯一，类型限定为 `HTTP_SERVICE`、`WEB`、`DESKTOP`、`MOBILE`。
- 同一 Target 内 Environment 名称唯一，endpoint 必须是无用户凭据的绝对 URI。
- Environment 的 `config` 仅保存非敏感运行配置；密码、Token、Secret 等只能通过 `secretRefs` 保存引用。
- 重复提交相同自然键与相同内容会返回既有资产；相同自然键但内容不一致返回 `STATE_CONFLICT`。

实体 DDL 负责项目资产表的初始化与字段补齐；后续兼容、索引和数据迁移放入
`src/main/resources/simple-migration`。
MySQL 表是项目资产的真相源。
