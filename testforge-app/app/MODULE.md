# app

TestForge 控制面的唯一可执行模块，负责 Spring Boot 启动、全局配置和九个 jar 模块的显式装配。

- 生成可执行 `bootJar`，普通 `jar` 关闭。
- 不拥有 Run、Task、Result 等业务实体。
- 业务行为必须留在对应模块，通过公开 Service、Port 或 Event 协作。
