# 治理与记忆系统验收

日期：2026-10-12。基线 `2e21351`；治理分支 `codex/project-governance-20261012`。本记录是本轮实测范围，不提升旧聊天报告为本轮结果。

## 清理后项目验证

| 检查 | 实际结果 |
| --- | --- |
| Parser | JDK17/Maven3.8.7、现有离线依赖 package 成功；清理前证明可重建，清理后再次从源码生成 |
| backend | package 成功；167测试、0失败/错误/跳过，显式启用真实Parser与隔离本地MinIO |
| sql-runner | package 成功；7测试、0失败/错误/跳过 |
| downloader | 8测试通过 |
| frontend | vue-tsc/生产构建、全源Prettier通过；大于500kB chunk警告保留 |
| Java分层/注释 | 172类、851方法/构造器有中文用途注释；分层约束通过 |
| 当前Web镜像 | 独立端口参数/非法端口、Service环境注入、无后端启动、后端上线与IP改变恢复、请求与Session/CSRF透传检查通过 |
| 仓库摘要 | app/web/downloader的0.0.4 manifest正文SHA256、Docker-Content-Digest、本地config ID相符 |
| 差异 | 运行源码、Dockerfile、依赖、实际部署配置无变更；没有新增应用镜像发布 |

首轮167项没有业务断言失败，但两个Parser用例的JUnit系统临时目录清理发生AccessDenied。通过为测试JVM传入 `-DargLine=-Djava.io.tmpdir=本任务独占目录` 并设置TEMP/TMP后完整重跑，全通过。无业务代码修改。

未执行本轮完整浏览器E2E、真实MySQL、生产Kubernetes/Operator/Flink作业或华为OBS验收；不能把组件测试、协议替身和历史现场单用例外推成全部版本支持。

## 无历史上下文检索演示

实际运行 `python scripts/check_agent_docs.py --demo savepoint`。脚本只从AGENTS/INDEX开始，匹配一行，读取关联需求、模块、ADR，再读取一个实现与一个相关测试；没有加载聊天或全部源代码。这是可复现的冷读取导航模拟，没有创建第二个AI聊天，不等于对任意Agent理解能力的保证。

输入关键词：`savepoint`。命中：总索引生命周期行→REQ-001→job-lifecycle→ADR-002。实际读取清单：

| 路径 | UTF-8字节 |
| --- | ---: |
| `AGENTS.md` | 3041 |
| `docs/agent/INDEX.md` | 2770 |
| `docs/agent/requirements/REQ-001-lifecycle.md` | 1553 |
| `docs/agent/modules/job-lifecycle.md` | 2816 |
| `docs/agent/decisions/ADR-002-safe-stop.md` | 1136 |
| `backend/src/main/java/com/flinkweb/service/job/runtime/impl/StopServiceImpl.java` | 17568 |
| `backend/src/test/java/com/flinkweb/WorkbenchIntegrationTest.java` | 47750 |

共7个文件、76634字节。默认入口AGENTS+INDEX共5811字节；本次5份知识文档共11316字节。源码/测试较大时，未来可在已定位文件内搜索具体方法，减少实际上下文加载；本演示为了可统计读取了整份选中文件。

从模块找到 `stop/advance/proceed/upgradeSavepointConfirmed/restartResource`；核实保存点完成证据、代数、UID/resourceVersion及先存恢复路径后删除。潜在影响是停止、刷新推进、恢复、草稿删除与操作记录；不需要默认读取数据源、用户管理、前端全部页面、全部REQ/ADR、node_modules或旧聊天。实际测试覆盖要到已定位测试文件进一步搜索Savepoint用例。

没有使用模型token计数器，不能给精确Token节省比例。可验证依据是2份默认入口、1条业务索引路由和局部代码定位，避免加载104KB的整份功能/部署文档以及全仓文件正文。

## 增量更新模拟

假设小需求：“JAR表单增加角色模板具名数组合并的就地说明”，不改真实业务代码。影响为REQ-002验收/变更、job-authoring模块说明、features相关段、current-state；有长期参考价值才添task-log。实现位置先查PodTemplateFields/PodTemplates。仅提示文字变化不新建ADR/需求/模块；路径和能力导航不变则总索引、模块/决策索引不改；SQL运行、S3协议、权限和迁移记录不更新。

实际运行 `--changed docs/agent/modules/job-authoring.md` 验证目标及直接引用文档，证明无需重建索引；检查结果在最终检查摘要中记录。若新增路径/新能力才更新对应索引，并检查旧/新路径的引用者。模拟没有改真实业务代码。

## 长期机制的边界

AGENTS保存原文强制规则；INDEX关键词→REQ/模块/ADR→真实代码；状态记录实测范围，未确认项单列；任务收尾按workflow增量更新。Python标准库检查本地链接、Markdown ATX锚点和原文规则，支持选择变更文档及引用者，不联网、不使用向量库或数据库。

语义仍需实际代码/测试和最新用户要求核实，路径检查不能保证业务文字永远正确。重大旧规则替代关系已在ADR-004标明，已删除独立初始化补丁不再作为当前路径。

## 最终知识与维护检查

- 全量知识/入口：31份文档，196个本地链接/锚点及AGENTS原文规则通过。
- 增量演示：仅4份目标/直接引用文档、56个本地链接/锚点通过；索引无需重新生成。
- 检查器受控验证：合法链接/锚点通过，缺文件、错锚点、越出项目路径被拒绝；同名标题追加锚点序号正确。
- 敏感值检查：部署清单固定初始密码/主密钥值没有复制进AGENTS、README、新知识文档或检查脚本；报告仅记变量名与待确认风险。
- 保留Parser与基线二进制逐字节哈希一致；Git diff（含暂存）格式校验通过。
- 本轮临时服务、网络、数据库及独占脚本/日志清理；共享依赖与原有数据/备份保留，已标记镜像保留。文件数量及执行清单见cleanup-report。

系统临时目录例外：首轮JUnit的两个目录删除仍AccessDenied，未变更系统ACL；具体路径记在open-questions。项目内独占目录/隔离MinIO/代理容器与网络已清理，不把系统目录误记为清理成功。
