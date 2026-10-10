from pathlib import Path
root = Path(__file__).resolve().parents[1]
p = root / 'docs/features.md'
s = p.read_text(encoding='utf-8').replace('更新：2026-10-09。', '更新：2026-10-10。', 1)
s = s.replace('部署渲染时将这两个引用转换为 Flink 主容器的 AWS Secret 环境变量，不把密钥明文写入 CR、预览或发布历史。', '2026-10-10：部署渲染时将这两个引用转换为 Flink 主容器的 AWS 环境变量，加密密钥直接展开为实际值提交到目标 namespace，不为这些值新建 Kubernetes Secret。预览恢复密钥占位符，发布历史隐藏环境变量字面值；停止前配置加密保存供按原配置重建。用户明确登记的既有 Kubernetes Secret 引用仍保留。')
s = s.replace('SQL、JAR 和 Session 共用登记的 Flink 镜像；Runner 从 S3 挂载至 usrlib，默认按镜像精确 Flink 版本定位。', 'SQL、JAR 和 Session 共用登记的 Flink 镜像；2026-10-10：Runner 必须显式选择，从共享 sql-runner 列表选择 JAR 或确认具体 HTTP/HTTPS/S3 地址，不再按镜像版本自动定位。SQL Application 与验证专用 Session 必须选择 Runner；普通 Session 可以不选。Runner 挂载至 usrlib。')
s = s.replace('Secret 环境变量与非 JAR 文件须先在 Session 配置中准备并重启', '环境变量与非 JAR 文件须先在 Session 配置中准备并重启')
s = s.replace('| YAML 密钥 | 容器 env.value 中使用 "{{secret:id}}"，后端转换为 Kubernetes Secret 引用 |', '| YAML 密钥 | 容器 env.value 中使用 "{{secret:id}}"；加密值在部署时直接展开，不生成 Secret，预览与历史脱敏；显式已有 Secret 引用继续保留 |')
s += '''
### 表单与文件选择（2026-10-10）

- JAR/SQL 表单的 last-state/savepoint 存储条件错误定位到“升级策略”；显式 savepoint 表单要求 Savepoint 目录，无状态作业可选 stateless。既有 YAML 不因该表单规则强制改写。
- 主 JAR 支持静态识别公开静态 main(String[]) 和有效 Program-Class 清单；一个入口自动填入，多个入口下拉选择，失败或没有候选时可手动填写。识别不加载或执行用户类，限制压缩读取量、解压总量、类和 Manifest 大小及归档项数。
- 程序、Runner、附加依赖及配置文件共用 S3 浏览与手动 HTTP/HTTPS/S3 地址确认。确认后按末层文件名显示标签；HTTP 主机须加入白名单，不接受凭据、查询参数或重定向。外部 S3 地址使用平台 S3 连接与凭据。空文件区缩小，保持现有卡片、按钮、字体和移动布局。
- 用户显式选择的附加文件保留在下载清单和共享 usrlib 卷中，包括 Presto S3 JAR；只迁移平台旧版标记的自动插件项，不再按文件名静默移除。运行镜像内 S3 插件的自动安装规则保留。
- JAR 开发列表独立查询当前 FlinkDeployment，按资源身份显示已部署、未部署或资源冲突；查询失败保留草稿并显示状态未知。查询不推进作业启停，不增加轮询。
- “全部文件”从制品桶根目录浏览，根目录可上传和创建目录；作业/共享快捷入口仍指向配置前缀下的 jobs/shared。专属文件复制、清理仍仅按平台 UUID 目录执行，不扩大到桶根目录。
- 已验证本地后端与浏览器回归、实际构建镜像的启动和页面；未以真实 Kubernetes/Flink/S3 作业验证本次部署效果。
'''
p.write_text(s, encoding='utf-8')
p = root / 'docs/deployment.md'
s = p.read_text(encoding='utf-8').replace('更新：2026-10-09。', '更新：2026-10-10。', 1)
s = s.replace('允许浏览的制品根前缀，默认 artifacts/', '平台作业/共享文件管理前缀，默认 artifacts/；全部文件从桶根目录浏览')
s += '''
### 文件与密钥行为更新（2026-10-10）

SQL Application 和验证专用 Session 的 Runner 改为手动选择：在 SQL Runner 文件选择区进入共享 sql-runner 目录，或确认 `https://host/path/runner.jar`、`http://host:port/path/runner.jar`、`s3://bucket/path/runner.jar`。不会按镜像版本自动选取；运行中 Session 仍需停止后修改并重新启动。普通 Session 可以不选择 Runner。

所有共用文件选择区均支持确认外部 HTTP/HTTPS/S3 地址，文件名自动取末层；地址不带凭据、查询参数或片段。HTTP(S) 主机须由管理员配置到 FLINK_WEB_ARTIFACT_HOSTS，读取不跟随重定向，静态识别和下载器均拒绝本机/链路地址。S3 外部桶使用平台现有 endpoint 与凭据，须授予相应单对象读取权限。制品桶浏览所需 ListBucket 范围现在覆盖桶根；作业自动复制/清理范围保持配置前缀下的 UUID 目录。

配置管理中的加密密钥在实际提交时作为容器 env.value 展开，不再生成 fw-* Kubernetes Secret；用户显式登记的已有 Secret 引用和运维配置的下载器凭据 Secret 保留。预览恢复密钥占位符，发布记录隐藏环境变量字面值，停止前配置使用现有主密钥加密保存。部署资源中的实际值按本次用户要求可在 namespace 中读取，namespace 读取权限由运维管理。本次未自动删除先前生成的 Secret，未更新实际 Kubernetes 工作负载。
'''
p.write_text(s, encoding='utf-8')
