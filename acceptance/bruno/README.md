# Bruno API 验收

当前集合已包含 AI 诊断的 Provider 状态、生成诊断和读取最近结果链路。运行前在 `Local` 环境填写真实 `runId`、`reportId` 和新的 `requestKey`。

```powershell
bru run ai-diagnosis --env Local
```

完整断言与异常矩阵见 [AI 诊断验收方案](../../docs/08-ai-diagnosis/04-验收方案.md)。可靠性全量 Bruno 集仍归属 `TFP-029`，不在本次 AI 模块范围内。
