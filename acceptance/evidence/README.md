# 验收证据

公开仓库不包含作者机器上的历史截图、运行日志或任务数据。初始清单为空，不代表业务流程已通过验收。

执行 `acceptance` 中对应验收脚本后，在本机保存结果，并将可公开的脱敏摘要登记到 `manifest.json`。每项记录 `id`、`status`、相对 `path` 与实际 `command`；只有真实通过且证据文件存在才可标记 `PASS`。

```powershell
python acceptance/evidence/build_index.py
python acceptance/evidence/build_index.py --check
```

生成的原始证据默认被 Git 忽略。主动发布摘要前检查令牌、个人路径、仓库地址、内网信息和截图内容；不要把已移除的历史记录视为当前版本验收。
