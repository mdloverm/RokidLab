# RokidLab Rules

## 每次任务前必须执行
1. 读取 `docs/纠错方案.md`（架构/检查清单/档案/变更日志）
2. 读取 `RULES.md` 第 10 节发布流程

## 核心约束
- ADB CMD_CLSE/CMD_WRTE 必须验证 `arg1 == localId`
- 所有 `while(true)` 必须有超时保护
- 新增资源（线程/Socket/Bitmap）用完必须释放
- 修改后必须更新 `docs/纠错方案.md` 的第四部分（项目档案）和第五部分（变更日志）
- 发布 vX.Y 严格按照 RULES.md 第 10 节 7 步执行

## Gitee Token
- `${GITEE_TOKEN}`（**禁止明文入库**；从环境变量 / 不入库的 `local.properties` 注入。2026-09-12 移除了此前明文写入的旧 token，旧 token 需到 Gitee 后台撤销轮换）
