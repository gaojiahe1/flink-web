# 制品与运行文件

更新：2026-10-20。关联 [需求](../requirements/REQ-004-storage-security.md)、[决策](../decisions/ADR-003-storage-security.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：S3 浏览/上传/复制/删除、外部文件引用、下载器与 S3 插件准备。S3StorageClient 单独负责存储协议，ArtifactService 管业务，ArtifactPodBuilder/S3PluginPodBuilder 渲染受管卷与容器；实际文件读取在 downloader/Flink 主容器。

制品浏览从桶根开始，专属路径仍为配置前缀/jobs/{UUID}/，共享为shared/。上传 If-None-Match 禁止覆盖；引用对象仅 HEAD/大小≤1GiB。整目录删除必须精确带末尾斜杠，禁止根/穿越，每批≤1000，单项失败必须提示。JobService.delete 清专属程序/状态路径；停止不清；复制不复制运行状态、保存点与源 ACL。已删对象不可被数据库回滚恢复。

HTTP(S) 需白名单，无凭据/参数/片段，不跟重定向，阻止危险目标；S3 外部单对象用平台 endpoint/凭据。Runner 不按版本猜路径。历史制品登记/API 是兼容入口，不能当无引用文件删除。

仅一个 fw-download initContainer：准备程序与启动脚本；主容器从自身 opt 自动发现唯一 Presto S3 JAR 复制独立插件卷，不把自动插件加入 usrlib。用户显式附加 Presto JAR 不按文件名静默丢弃。运行 YAML 开关、角色覆盖与挂载冲突见模板模块。

平台 SDK、下载器、Flink 插件连接分别验收；V4/签名区域/WHEN_REQUIRED/必要 MD5 只说明兼容配置，MinIO 测试不能替代华为等提供商认证。10-10 用户反馈目标华为 S3 添加两个 WHEN_REQUIRED 环境变量后恢复正常，仅为该场景现场反馈，不等于全部接口或三条路径已验收。已知未确认资料见 open-questions。

2026-10-20追加：删除平台作业继续在确认集群资源消失之后清理，由JobService事务处理失败回滚/重试；S3StorageClient对已验证UUID同时清理`{prefix}jobs/{id}/`和旧版`{prefix}{id}/`。先删不带末尾斜杠的精确目录标记，再递归分页删对象及带斜杠标记，避免某些S3实现的同名对象遮蔽目录。只从可信job id计算目标，不按YAML推导路径，不清相邻前缀或共享文件；SQL仍存草稿时先迁移其引用文件的既有规则保留。真实隔离MinIO及最终镜像删除API验证覆盖目录标记/旧目录/邻居/共享边界。

## 定位代码与验证

- [S3StorageClient.java](../../../backend/src/main/java/com/flinkweb/client/storage/S3StorageClient.java)
- [S3FailureDiagnostics.java](../../../backend/src/main/java/com/flinkweb/client/storage/S3FailureDiagnostics.java)
- [S3FailureDiagnosticsTest.java](../../../backend/src/test/java/com/flinkweb/client/storage/S3FailureDiagnosticsTest.java)
- [S3ServiceTest.java](../../../backend/src/test/java/com/flinkweb/S3ServiceTest.java)
- [ArtifactServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/artifact/impl/ArtifactServiceImpl.java)
- [ArtifactPodBuilder.java](../../../backend/src/main/java/com/flinkweb/service/artifact/support/ArtifactPodBuilder.java)
- [S3PluginPodBuilder.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/S3PluginPodBuilder.java)
- [JobServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/impl/JobServiceImpl.java)
- [download.py](../../../downloader/download.py)
- [flink-bootstrap.sh](../../../downloader/flink-bootstrap.sh)
- [ArtifactPicker.vue](../../../frontend/src/modules/artifact/components/ArtifactPicker.vue)
- [S3DirectoryLiveTest.java](../../../backend/src/test/java/com/flinkweb/S3DirectoryLiveTest.java)
- [test_download.py](../../../downloader/test_download.py)

2026-10-12：S3StorageClient 从 platform.operator.state-root 取得 FLINK_WEB_STATE_ROOT，默认值与 UUID 状态清理均保留完整前缀。VariableRenderer 将 Flink S3 AK/SK 在 flinkConfiguration 原位置展开并清理主容器旧 AWS AK/SK；下载器 env 鉴权保留。已有 Secret 引用须 GET 读取指定键，明文仅在本次提交内；预览和历史隐藏。

2026-10-13 来源补核：下载器无 SHA-256 内容校验不等于禁用 S3 协议 checksum、交付文件哈希或镜像摘要核对。生成 YAML 不注入 AWS_DEFAULT_REGION；当前下载器可读取该变量作为旧环境兼容回退，受管 S3 清单注入 AWS_REGION 用于签名。系统设置全局下载器绑定替代每条 Flink 镜像配置；唯一 fw-download 准备脚本，主容器从自身镜像复制插件的原因及历史方案替代见 ADR-003。复制专属目录含子目录和未引用文件，实际目录操作定位 S3StorageClient.copyJobFiles/deleteJobStorage；本轮只读核对，10-05/10-08 隔离 MinIO 及现场 test2 历史验证见验收记录。

本聊天补核（2026-10-13）：当前 S3Client 显式配置 WHEN_REQUIRED、LegacyMd5Plugin、path-style 和禁用分块编码，下载器也设置 s3v4/WHEN_REQUIRED；生产旧镜像通过两个环境变量修复的记录不表示当前代码仍缺此配置。S3FailureDiagnostics.failure/trace 已有结构化诊断及安全原因链/栈帧/suppressed，测试定义覆盖脱敏、循环链和批量删除 HTTP200 下的单项错误，本轮未执行。`artifacts/` 是对象前缀，启动不创建空目录，首次写对象自然形成；桶须预建，平台没有 CreateBucket。环境密钥只初始化缺失的配置管理项；切换存储不自动搬对象、更新已有作业/Session 或替换已保存密钥。详见 [部署指南](../../deployment.md#更换-s3-兼容服务2026-10-10)。

## BES 运行时与后端镜像（2026-10-19）

后端使用用户提供的未修改 BES bundle，内部坐标/来源校验见 [依赖说明](../../../backend/vendor-repository/README.md)；[POM](../../../backend/pom.xml) 排除 starter-tomcat 及独立 tomcat-embed-el。BES bundle 自带 Jakarta Servlet/EL，EL类名仍有Apache命名，以代码来源检查确认来自BES bundle，不按包名前缀猜容器。

[运行目录适配](../../../backend/src/main/java/com/flinkweb/config/server/BesServerConfiguration.java) 将 FLINK_WEB_BES_BASEDIR（默认java.io.tmpdir/flink-web-bes）传给工厂，并同步BES优先读取的installRoot，避免上下文沿用旧目录。该版本仍无条件相对user.dir创建文档根，因此将/app/appserver链接至/tmp，保留/app工作目录及相对路径语义；数据库明确使用/app/data/flink-web.db，Parser/Runner保持绝对路径。只读根及UID10001不变，不把授权资源提取到知识库。

[Dockerfile](../../../backend/Dockerfile) 使用干净Kylin/JRE，移除重复业务层及额外软件包安装；Spring Boot工具拆分依赖/应用。默认源码构建；离线发布可用命名context覆盖artifacts阶段，目录必须含经过相应校验的app.jar、runner.jar和双版本validators。后续发布必须使用此正式Dockerfile，不能FROM上一版app再覆盖JAR，否则旧内容仍在历史层。实测1,679,404,280→585,001,401字节，25→10层；压缩层1,269,253,098→282,274,156字节。体积是同一Docker/registry口径，不是Docker全盘占用。

[真实BES回归](../../../backend/src/test/java/com/flinkweb/BesServerIntegrationTest.java) 核对实际服务器、显式端口、目录、EL来源及真实HTTP；最终镜像另验证登录/Session/CSRF、3MiB MinIO上传下载、双Parser及Runner。完整范围见 [验收](../validation.md#bes-运行时与镜像瘦身2026-10-19)。

2026-10-19 追加要求：app、web、downloader统一发布0.0.7，替代0.0.4。三个运行镜像均从干净Kylin基础层构建；[Web Dockerfile](../../../frontend/Dockerfile) 通过 [导出脚本](../../../frontend/prepare-nginx.sh) 仅复制Nginx及所需额外运行库，避免整套安装层和历史dist；[Downloader Dockerfile](../../../downloader/Dockerfile) 用 [精简脚本](../../../downloader/prepare-runtime.py) 清理CPython测试/开发静态库/安装工具及缓存，再strip，保留boto3服务数据和标准库。Web逻辑体积446,050,884→310,963,280字节；downloader760,604,373→392,503,474字节。正式源码构建与离线验证产物共用Dockerfile，不能从旧业务镜像覆盖。全部0.0.7已推送并核对所有层；清单引用更新，实际工作负载/现有系统下载器绑定未修改，升级注意保存配置优先于环境变量。摘要和命令见 [部署指南](../../deployment.md#bes-运行时与镜像瘦身2026-10-19)。

同日按用户授权清理其他版本：仓库三组件仅保留0.0.7，旧标签与63份历史manifest移除后离线GC回收约5.44GB。app旧标签与保留版本共享摘要，须按tag解绑，不能按共享digest删除；GC前核实所有仓库保留引用，其他仓库/本机镜像/实际工作负载未改。维护方法及验收见 [部署指南](../../deployment.md#仓库旧版本清理2026-10-19)。
