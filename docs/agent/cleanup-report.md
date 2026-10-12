# 文件治理报告

审计日期：2026-10-12。基线：`2e21351`；工作区原先干净；治理分支：`codex/project-governance-20261012`。

## 审计范围与方法

首次治理按顶层、构建、业务模块分层检查；枚举依赖/输出数量但不加载依赖或 Git 对象正文。核对 Spring Bean、spring.factories、Parser 独立进程、Runner 服务发现、路由兼容、Docker COPY、部署补丁和数据库迁移引用。对 338 个受跟踪文件做非空内容 SHA-256 去重，仅发现下表的两份 Parser 输出完全相同。未以“无静态引用”作为删除依据。

## 清理前方案（先记录，再执行）

| 路径/分组 | 类型 | 分类 | 当前用途和判定依据 | 风险 | 操作 | 状态 | 验证 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| backend/src、frontend/src、downloader、sql-runner/src、sql-validator/src | 源码/测试 | A | 服务、路由、业务和执行入口均在使用 | 高 | 保留 | 已保留 | 原有构建与回归 |
| backend/src/main/resources/db/{mysql,sqlite}/V1…V5、schema.sql | SQL | A | SchemaInitializer 顺序迁移；schema.sql 被测试/安装文档引用，两种方言有区别 | 高 | 保留 | 已保留 | 数据库/平台集成测试 |
| backend/src/main/resources/META-INF/spring.factories | 动态注册 | A | 注册 StartupFailureAnalyzer | 中 | 保留 | 已保留 | 启动检查/代码核对 |
| sql-validators/1.20.3/validator.jar | 二进制运行资源 | A | SqlParserServiceImpl 按精确版本查找；默认本地安装资源，不等于 target 缓存 | 中 | 保留 | 已保留 | 真实独立 Parser 测试 |
| deploy/kubernetes/frontend-runtime-patch.yaml | 部署补丁 | A | deployment.md 仍引用，兼容旧 Deployment 与新 StatefulSet | 中 | 保留 | 已保留 | 本地路径检查 |
| deploy、所有 Dockerfile、scripts | 部署/维护 | A | Compose/Kubernetes、代理/DNS、SQL Session、中文注释检查入口 | 中 | 保留 | 已保留 | 引用/检查脚本 |
| sql-validator/target 下 8 个受跟踪文件 | 编译输出 | B | POM 可离线重建；Docker 构建使用源码重新打包；安装文档要求先 package | 低 | 删除 Git 中的输出，加入忽略规则 | 已移出版本管理，本地重建忽略 | 已先重建 BUILD SUCCESS；清理后再构建 |
| .tools 中下列 43 份日志 | 历史构建/测试输出 | B | 识别 UTF-8/UTF-16；存在 Maven/Vite/Prettier/Playwright 生成标记；无活动 Java 服务；有效源码未引用这些日志 | 低 | 逐文件核对哈希后删除 | 已删除 | 新运行的检查、日志删除清单 |
| .tools/frontend-refactor-backup、module-refactor-backup、streampark-reference、历史修改脚本 | 备份/参考代码 | C | 未受 Git 管理，可能是唯一备份或用户参考；不能以名称判废弃 | 中 | 保留待确认 | 未删除 | 人工确认后另行备份/审计 |
| data、backend/data、.tools/*.db、*.lock、测试目录及未知日志/令牌文件 | 本地数据库/运行资料 | C | 尚未证明每份数据均可丢弃，不读取凭据正文 | 高 | 保留待确认 | 未删除 | 数据归属/备份与进程检查 |
| .idea、node_modules、.tools/m2 | IDE/依赖缓存 | A | 本地工具/离线构建依赖，删除收益不足 | 低 | 保留 | 已保留 | 离线构建 |
| deploy/kubernetes/application.yaml 的固定初始化凭据/主密钥 | 配置 | C | 非空固定值，来源及是否用于实际环境未确认；已有加密数据依赖原主密钥 | 高 | 不复制值到知识库；保留待轮换方案 | 未修改 | 确认真实使用及备份后处理 |
| docs/features.md、docs/deployment.md 的历史叙述 | 文档 | D | 保存决策与验证边界，存在按日期追加内容 | 低 | 新索引用链接复用；不大规模改写 | 已保留 | 新索引不重复数值/业务全表 |
| sql-validator/target 两份 shaded JAR | 重复输出 | B | 治理前 SHA-256 完全相同 | 低 | 随输出清理 | 已移出版本管理 | 非空文件哈希比对 |

目录职责已清晰；不移动业务包、测试或配置，不改变 REST API、运行行为和数据库结构。中高风险项未获逐项删除确认，全部保留。删除的受跟踪产物可从基线 Git 恢复；临时构建/测试日志可重新生成。没有使用 reset --hard、git clean 或删除未知目录。

## 清理前统计

统计口径：枚举项目实际普通文件，排除 `.git` 和符号链接；依赖、忽略目录包含在物理文件数量中。审计脚本本身产生了 1 个临时文件，基线中已计入；因此物理总数是本次审计时快照，不冒充纯源码数。

- 受跟踪文件：338。
- 物理文件：11174；字节：627612854。
- 计划移除受跟踪产物：8 个、52,445,912 字节。
- 计划移除已识别历史日志：43 个、11075910 字节。
- 合并/移动业务文件：0。

## 逐文件清单

以下哈希来自修改前基线；下面标记实际执行结果；target 同路径新输出是清理后重新生成的忽略文件。不会保留日志正文、数据库正文或凭据。

| 文件 | 原字节 | SHA-256 | 状态 |
| --- | ---: | --- | --- |
| `sql-validator/target/flink-web-sql-validator-0.1.0-shaded.jar` | 26217936 | `42709688957a412b94f5b4895a8b09bbb53aae14737c4bb95fc220be12061dd4` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/flink-web-sql-validator.jar` | 26217936 | `42709688957a412b94f5b4895a8b09bbb53aae14737c4bb95fc220be12061dd4` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/original-flink-web-sql-validator.jar` | 4899 | `3722c1a6f9ebae345123b603c1b04f4041e9b482c4766dfd233622a4dfc164a4` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/classes/validator.properties` | 21 | `529674dcf785c790429e17047f47e1e0cdce0ada79821ecb6c7cc8f31354522b` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/classes/com/flinkweb/validation/ParserMain.class` | 4850 | `3d753bd7ffcd0f256e6bba057bee9c8df658ce0acabdd3b852d50e3c97529881` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/maven-archiver/pom.properties` | 125 | `e43bc0271dc2c0b66384c446624251505e5f5c1fba45b461b66f0b45e9d8fadc` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/maven-status/maven-compiler-plugin/compile/default-compile/createdFiles.lst` | 41 | `23f9aa1a68e289b71765926398fd38c9c322b972a4713843bf3dcee5e7e80798` | 已移出 Git；重新生成后忽略 |
| `sql-validator/target/maven-status/maven-compiler-plugin/compile/default-compile/inputFiles.lst` | 104 | `776ed712efe26aa7265977dc0b1d4c43f49226ad24bc5d3ff9acb499a0b13426` | 已移出 Git；重新生成后忽略 |
| `.tools/browser-regression.log` | 11752 | `5fbba1b0a726a1a4e8af9a74d6ffc56481d2790292c17f823a8b46e99d03b6de` | 已删除 |
| `.tools/catalog-browser-retry.log` | 1984 | `a1bd807e37fff4aef6fc2f64e27c16bee375a87ff00f3f71d870ca2cdcbf005c` | 已删除 |
| `.tools/catalog-browser-tests.log` | 14674 | `be95afa19204a72b9a198cb1527c56ceb7b2b270dff5f1ecb0024f578367cc92` | 已删除 |
| `.tools/catalog-frontend-final.log` | 6002 | `1e525e63cb8db0fe542943bf12236a3a041d0163796004a087cc8fe10763395c` | 已删除 |
| `.tools/catalog-metadata-browser.log` | 1374 | `8f4bc92425e8d14d79b6a7526659e74ca763e42880e0dd10036855d5558c248c` | 已删除 |
| `.tools/data-final-build.log` | 7532 | `dd76edcab1d11c298511229fc39f4c4abda0cb9997aa427fd9109478a0969684` | 已删除 |
| `.tools/data-final-format-check.log` | 276 | `350afc1b633016c7d26491e7327182f878ee66ed497cf6bc3ebf56117a44c162` | 已删除 |
| `.tools/data-ui-final-retry.log` | 7144 | `ca9f72f4b2a8ff3d32e58ba7c17d2fbfda54b908be06ffd0a57b76582d42e933` | 已删除 |
| `.tools/data-ui-final-tests.log` | 12760 | `1f06b4934f9ffde2602c1bb84671262bb96edc48e2c29937d72197445aa78baa` | 已删除 |
| `.tools/data-ui-last-retry.log` | 1612 | `46077f6032c2e9b3ad0f73777dc3cd7e78bbbfd324194292832843e42aced0f7` | 已删除 |
| `.tools/data-ui-retry.log` | 5296 | `126ff1d03f527d4d4f09519a3c4267f163fb527b15a9276962dd00ebf5fda7e8` | 已删除 |
| `.tools/data-ui-tests.log` | 13366 | `db4cf4ff92944f6e378d028d39453efef2add1f2b471ba173bc110aee933d75c` | 已删除 |
| `.tools/data-upgrade-tests.log` | 24060 | `36b6ae6c8708a833d47598e18cdee613a1a48bee4c8a074d6fa03a0545cdcf56` | 已删除 |
| `.tools/data-upgrade-ui-build.log` | 6004 | `e3737d05378141c331a9c810576ff0c7b91dc9af041072ceb76c84098649042f` | 已删除 |
| `.tools/deploy-format.log` | 1140 | `24ba100eb3a3c59620fe1e877d716c879aff5be5eb5a91e138fcacbaa959425a` | 已删除 |
| `.tools/operator-browser-next.log` | 16642 | `afe77842eac09e6b2bcf487841f9fe363d4944d1c7b7b6fb0575ba4ac87ce506` | 已删除 |
| `.tools/operator-browser-retry.log` | 3118 | `f3fa37334c707767736677fa718192033cca84adbbf60839ac717d27e1180cf6` | 已删除 |
| `.tools/operator-browser.log` | 14762 | `da22acb9ea635b1cd0d79158d7c4aa80fb9ab53a5cd76f5940e1a72a3d79c85e` | 已删除 |
| `.tools/operator-format-check.log` | 276 | `350afc1b633016c7d26491e7327182f878ee66ed497cf6bc3ebf56117a44c162` | 已删除 |
| `.tools/operator-frontend.log` | 6952 | `bef498572ebe7bd48a7ea7a0babe942c2ef9609586f34d06e22c1afe0b6cdf95` | 已删除 |
| `.tools/operator-tests.log` | 27888 | `e504db632ca4d612bc83486ab6f4b235c604e9b516eacbba212a3efedcfe9062` | 已删除 |
| `.tools/password-browser-focused.log` | 1866 | `6c347f3cd9cec84a148961b9f3472bb3dc90cebfae9726ad991d3a0bbf4ec5bd` | 已删除 |
| `.tools/password-browser.log` | 7002 | `f001186975d5820f60b879c8dbf557a9e921eeec702d7621d35adaff04c7a1e5` | 已删除 |
| `.tools/password-format.log` | 276 | `350afc1b633016c7d26491e7327182f878ee66ed497cf6bc3ebf56117a44c162` | 已删除 |
| `.tools/password-frontend.log` | 6336 | `3d88540e7f4fbdc30e8161d0bf8472e2afd04860075a6a7f7a45b61fffa3a5e5` | 已删除 |
| `.tools/refactor-browser-tests.log` | 4874 | `4fb820995915fe1530baea6f06b9ac33347dc62e58bc9b1bcb5bb39a8dcf71b5` | 已删除 |
| `.tools/refactor-compile.log` | 10818 | `944444d549ec293d182ae13d86c6aa32b7ddeb6ce58d0ea1907e4f10f12f2285` | 已删除 |
| `.tools/refactor-frontend-build.log` | 5528 | `33eebf080b8a52b549abdc9c8af8bcc2464f98e90af95df65d26ba1edc3c7cde` | 已删除 |
| `.tools/service-pods-backend-final.log` | 2644946 | `216d405741bc9a67434e5987712023480582ad796dea8135eb67e52de326f876` | 已删除 |
| `.tools/service-pods-backend-verified.log` | 2630912 | `ceb258b9d3e6f4649514b4c99571cae23309dc314fe8136d320d3f22d63862f4` | 已删除 |
| `.tools/service-pods-backend.log` | 2657450 | `67e2cd6c79e854f5321947b73a5c2fc99610078543513721e76818cb3399708c` | 已删除 |
| `.tools/service-pods-browser-final.log` | 24116 | `34feb43216e0a61d48b04705356e7d83ec43e88ac4fbfceabc8461a923f1dc03` | 已删除 |
| `.tools/service-pods-browser-pass.log` | 4696 | `1d339ea112de49e368b6941e86253118f7636bd476b607a518d2817feac7337d` | 已删除 |
| `.tools/service-pods-browser-verified.log` | 24680 | `614ac47bcbd9ae81d918aef5ac176d9b20bcad07589939a07bedfb2086ceeae1` | 已删除 |
| `.tools/service-pods-browser.log` | 47624 | `e4383c99d5b7ec6e13bfe70111374d117aea724a0a7a4e011a507f5009e67e3e` | 已删除 |
| `.tools/service-pods-frontend-release.log` | 8962 | `43d77ff9725bf635a18fc24f261ec1de0bcbfbc2ae7a20ed6b1a87a3f85f88a2` | 已删除 |
| `.tools/service-pods-image-browser.log` | 4900 | `2a627e211b1e2e0b79c05a8138b38da12e355da5c8f72607c11cdf2daf034e9e` | 已删除 |
| `.tools/service-pods-new-browser.log` | 5548 | `5f56766be32b2f060aced37947a53c4d72ef430a6517b3430f7cdb5b654a4e6b` | 已删除 |
| `.tools/service-pods-release-backend.log` | 2631726 | `7e6156a9996a031bf94b59f552965a15dfe13c05a708ac86f8f3e9a711231d07` | 已删除 |
| `.tools/service-pods-sql-browser-final.log` | 1518 | `3efd7e27363dbf019cdc359a169604c6e88ba36a5ff52445d1a791fd4cb10660` | 已删除 |
| `.tools/service-pods-visual-check.log` | 1908 | `aede9096ed0ed212fb2b9aa3cf700dd66a7e0028ad540c0b717e4710978221fa` | 已删除 |
| `.tools/service-pods-web-build.log` | 151578 | `c0d1f843e29cb0bf1be06fbbcb7fbcef2957aa9e44d3dae1aebd0456c77efa67` | 已删除 |
| `.tools/service-pods-web-final-build.log` | 14032 | `c2b65d738f7b04a03098ecb457cceb0870d0306188eac3e405036fe18611d0b0` | 已删除 |

## 实际结果与验证

实际处理 51 个基线文件：8 个 Parser 输出从 Git 移除，43 个历史生成日志逐文件哈希核对后删除（共 11,075,910 字节日志）。运行 Parser 原文件保留。两份完全相同的被跟踪 JAR 输出不再进入仓库；本地测试重建会在被忽略 target 重新生成必要输出，不能把52,445,912字节宣称为永久磁盘回收量。

没有业务文件合并/移动，没有中高风险删除。`.tools` 共享依赖与未知备份/数据库原样保留。没有受影响的应用镜像内容，现有三份0.0.4仓库摘要重新核对一致，没有发布或操作实际工作负载。

清理后 Parser/后端/Runner 打包通过，后端167、Runner7、下载器8项全部通过，前端类型/生产构建/格式、Java分层/中文注释与容器代理验证通过。本轮最终目录与链接检查见 [验收记录](validation.md)。首轮临时目录权限错误经显式测试JVM独占临时路径处理后全量重跑成功。

## 清理前后对比

| 指标 | 清理前 | 最终治理状态 |
| --- | ---: | ---: |
| Git受跟踪文件 | 338 | 358 |
| 物理普通文件（不含.git） | 11174 | 11404 |
| 已移出Git的生成文件 | 0 | 8 |
| 新增知识文档/维护脚本 | 0 | 28 |
| 已删除历史生成日志 | 0 | 43 |
| 业务文件合并/移动 | 0 | 0 |

最终物理数量在清理本任务独占临时目录后实际统计；包含重建输出及原有依赖/缓存。清理前包含1个审计临时脚本，差异已披露。执行测试重新生成大量target/报告，因此物理文件数可能增加；不把它当作源码膨胀或伪造“总文件减少”。新增的是27份有内容的导航/模块/需求/决策/验收文档和1个维护脚本，业务源码目录保持原位。工作树内target重新生成但不再由Git跟踪。字节会因打包/报告变化，不能用前后差额估算永久磁盘收益。

本轮临时MinIO、代理验证容器/网络均已移除；本任务独占脚本、日志、临时库与打包副本收尾移除。未删共享缓存/备份，Docker构建缓存核对为0，已标记镜像保留。

系统临时目录例外：首轮JUnit两个目录直接删除被Windows拒绝，具体路径与用途见open-questions；不将其误记为已清理。未改系统ACL。Git历史仍保留旧生成文件以便追溯，本轮没有改写历史。
