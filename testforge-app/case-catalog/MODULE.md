# case-catalog

拥有原子 Case、Script Version、无序 Suite、Workflow 草稿与不可变发布版本。

依赖 `common` 和 `project-catalog` 的公开查询边界，负责 DAG 校验与可执行快照编译。

## 包边界

- `io.testforge.casecatalog.testcase`：原子 Case、不可变 Script Version、标签与参数 Schema 子域。
- `io.testforge.casecatalog.suite`：无序 Suite、成员引用、标签与参数绑定子域。
- `io.testforge.casecatalog.workflow`：Workflow 草稿、发布版本、DAG 校验与执行快照子域。
- `io.testforge.casecatalog.ctrl`：Case 与 Suite REST API 共用的成功、错误响应模型。
- 三个子域分别保留 `ctrl`、`entity`、`model`、`repo`、`service`、`event` 包；领域实现不得越过公开 Service、Model 或 Event 直接访问另一子域内部。
- `CaseCatalogConfig` 是模块唯一对外装配入口，统一导入三个子域配置。

Case 子域不依赖 Suite 或 Workflow 子域；Suite 通过 Case 子域公开 Service 校验成员存在性与 project/target 归属，不跨包访问 Case Repo；Workflow 可以通过公开查询边界解析用例和套件引用。模块只通过 `project-catalog` 的公开查询 Service 校验项目与被测对象，禁止访问其 Controller、Entity 或 Repo。

当前已实现 TestCase、不可变 ScriptVersion、无序 TestSuite，以及 Workflow 草稿保存、DAG 校验、组合节点展开和不可变发布版本。Workflow 发布通过公开 Service 固定 Case/Script、Suite 与已发布 Subflow 引用，并以 `compiledSnapshot + checksum` 作为下游运行编排的稳定输入。
