# 制品与运行文件

更新：2026-10-12。关联 [需求](../requirements/REQ-004-storage-security.md)、[决策](../decisions/ADR-003-storage-security.md)；来源：当前代码与 [功能说明](../../features.md)。

职责：S3 浏览/上传/复制/删除、外部文件引用、下载器与 S3 插件准备。S3StorageClient 单独负责存储协议，ArtifactService 管业务，ArtifactPodBuilder/S3PluginPodBuilder 渲染受管卷与容器；实际文件读取在 downloader/Flink 主容器。

制品浏览从桶根开始，专属路径仍为配置前缀/jobs/{UUID}/，共享为shared/。上传 If-None-Match 禁止覆盖；引用对象仅 HEAD/大小≤1GiB。整目录删除必须精确带末尾斜杠，禁止根/穿越，每批≤1000，单项失败必须提示。JobService.delete 清专属程序/状态路径；停止不清；复制不复制运行状态、保存点与源 ACL。已删对象不可被数据库回滚恢复。

HTTP(S) 需白名单，无凭据/参数/片段，不跟重定向，阻止危险目标；S3 外部单对象用平台 endpoint/凭据。Runner 不按版本猜路径。历史制品登记/API 是兼容入口，不能当无引用文件删除。

仅一个 fw-download initContainer：准备程序与启动脚本；主容器从自身 opt 自动发现唯一 Presto S3 JAR 复制独立插件卷，不把自动插件加入 usrlib。用户显式附加 Presto JAR 不按文件名静默丢弃。运行 YAML 开关、角色覆盖与挂载冲突见模板模块。

平台 SDK、下载器、Flink 插件连接分别验收；V4/签名区域/WHEN_REQUIRED/必要 MD5 只说明兼容配置，MinIO 测试不能替代华为等提供商认证。已知未确认资料见 open-questions。

## 定位代码与验证

- [S3StorageClient.java](../../../backend/src/main/java/com/flinkweb/client/storage/S3StorageClient.java)
- [ArtifactServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/artifact/impl/ArtifactServiceImpl.java)
- [ArtifactPodBuilder.java](../../../backend/src/main/java/com/flinkweb/service/artifact/support/ArtifactPodBuilder.java)
- [S3PluginPodBuilder.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/support/S3PluginPodBuilder.java)
- [JobServiceImpl.java](../../../backend/src/main/java/com/flinkweb/service/job/creation/impl/JobServiceImpl.java)
- [download.py](../../../downloader/download.py)
- [flink-bootstrap.sh](../../../downloader/flink-bootstrap.sh)
- [ArtifactPicker.vue](../../../frontend/src/modules/artifact/components/ArtifactPicker.vue)
- [S3DirectoryLiveTest.java](../../../backend/src/test/java/com/flinkweb/S3DirectoryLiveTest.java)
- [test_download.py](../../../downloader/test_download.py)
