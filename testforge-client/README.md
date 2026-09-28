# testforge-client

TestForge 的 Vue 3 + TypeScript Web Console。业务页面和 API 按 `src/<feature>` 归属，`src/app` 只负责应用装配，跨业务复用能力进入 `src/common`。

```powershell
npm ci
npm run build
npm run dev
```

本地开发页面为 `http://127.0.0.1:5174`，`/api` 和 `/actuator` 代理到 TestForge 后端 `http://127.0.0.1:8081`。
