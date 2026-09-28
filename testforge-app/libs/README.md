# 随仓库提供的框架 JAR

TestForge 通过 Gradle 文件依赖使用这两个普通 JAR；无需安装私有 Maven 构件，不增加 Gradle 子模块，不携带框架源码。

| 文件 | 原始构件坐标 | 用途 |
| --- | --- | --- |
| `commons-1.0.1.jar` | `yhc.framework:commons:1.0.1` | 为迁移模块提供 BaseEntity、BaseRepo 等基础类型 |
| `simple-migration-1.0.1.jar` | `yhc.framework:simple-migration:1.0.1` | 扫描 SQL、记录执行历史并在启动时执行迁移 |

来源：框架维护者提供的 1.0.1 普通构建产物（原文件名带 `-plain`），2026-09-28 纳入仓库，内容未修改。校验值见 `SHA256SUMS`。JAR 内仅包含类文件、目录和 manifest，没有打包配置文件、源码或第三方 JAR。凭据规则扫描未发现高置信度密钥；这不等于完整漏洞审计。

## 引用

本项目 `app/build.gradle` 已配置：

```groovy
implementation files(
    rootProject.file('libs/simple-migration-1.0.1.jar'),
    rootProject.file('libs/commons-1.0.1.jar')
)
```

这里的 rootProject 是 `testforge-app`。其他项目引用时按其目录调整路径。

文件依赖不解析 Maven POM，也不自动引入传递依赖。TestForge 当前迁移链路使用 Spring Boot、Spring Data JPA、Jakarta Persistence/Annotation、SLF4J，由平台已有依赖提供。`commons` 中其他工具可能依赖 Hutool、Sa-Token、POI 等库；使用者若调用这些额外能力，需要自行补充对应依赖，不能把本目录视为完整 commons 开发 SDK。

## 更新与验证

维护者从框架项目重新构建普通 JAR，确认版本与内容后替换文件，同步修改 Gradle 路径及 SHA256SUMS。提交前执行后端测试、bootJar 构建及真实 MySQL 启动，确认迁移执行和重复启动行为。

```powershell
Get-FileHash .\testforge-app\libs\*.jar -Algorithm SHA256
```

本次仅改变依赖交付方式，原迁移 SQL、执行顺序与历史表保持不变。框架完整源码不随本仓库发布。

## 许可状态

这两个自有框架二进制文件随项目按 [Apache-2.0](../../LICENSE) 许可提供。框架源码不随本仓库分发；外部依赖仍遵循各自的许可证。
