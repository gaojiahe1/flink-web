# Flink Web 部署指南

更新：2026-10-26。功能与权限规则见 [功能说明](features.md)。以下路径均相对项目根目录，示例凭据由部署者自行提供。

当前 app/web 已按Pod诊断与证据边界修复覆盖发布0.0.8，downloader不变；用户确认集群关闭、先发布镜像，本轮未更新实际工作负载。最新结果见 [本轮发布](#pod诊断与证据边界修复2026-10-26)，历史结果各自保留。

## 1. 部署拓扑与前置条件

~~~text
浏览器 → 节点 IP:30080 → flink-web-0 / 前端 Nginx:8080
                         ├─ /api → 同 Pod Spring Boot:8081 → 元数据库 / Kubernetes API / S3
                         └─ /flink-ui/{jobId}/ → 后端鉴权代理 → Flink REST Service
~~~

2026-10-09：主清单使用单副本 StatefulSet/flink-web，前后端保留独立镜像，在同一个 Pod（flink-web-0）中运行。前端监听 8080，后端通过 SERVER_PORT 监听 8081；前端代理固定到 127.0.0.1:8081。原 NodePort 30080 和后端 Service 的 8080 入口保持兼容；后端 Service 转发到 backend-http/8081。新增 flink-web-headless 为 StatefulSet 提供固定网络身份。SQLite 继续复用原 flink-web-data PVC，不使用新建 volumeClaimTemplates，也不支持增加副本。Kubernetes Service 提供四层转发，按 URL 路径分流仍由前端 Nginx 完成。

部署前准备：

- 运维已安装 Operator/CRD；平台不负责安装、升级 Operator 或调整 Webhook。
- 一个作业 namespace、Flink 工作负载 ServiceAccount 及其 RBAC。平台通过 ServiceAccount 获取权限；可用 rbac.yaml 创建独立 flink-web 身份，或使用已授权的运维身份。
- Flink 运行镜像及匹配 JDK、Connector、Planner、S3/文件系统插件。
- 制品桶、状态存储目录及相应访问身份。
- SQLite 所需可写 PVC，或 MySQL 8+ 数据库及专用账号。

Flink UI 上游固定为 {FlinkDeployment资源名}-rest.{作业namespace}.svc:8081。代理只允许受控只读请求，不接收任意 URL，不转发平台 Cookie/Authorization；运行配置脱敏，环境接口被阻止；JM/TM 日志仅按角色、作业运维权限及白名单开放，并限制大小、脱敏及禁止缓存。不同 Flink UI 版本需实际验收。

## 2. 本地开发启动

需要 JDK 17、Maven 3.8+、Node 22.12+ 或兼容 package.json engines 的版本。先在 PowerShell 准备后端环境：

~~~powershell
$env:JAVA_HOME = '你的 JDK17 目录'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
$env:FLINK_WEB_ADMIN_PASSWORD = Read-Host '首次管理员密码（至少12字符，UTF-8最多72字节）'
# 仅新环境生成；已有加密数据时，注入之前保存的同一主密钥。
$keyBytes = New-Object byte[] 32
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
$rng.GetBytes($keyBytes)
$rng.Dispose()
$env:FLINK_WEB_MASTER_KEY = [Convert]::ToBase64String($keyBytes)
mvn -f backend/pom.xml spring-boot:run
~~~

妥善保管主密钥。初始用户名默认 admin，初始密码只在用户库为空时使用；重启不会覆盖已修改密码。SQLite 默认路径为后端进程工作目录下 ./data/flink-web.db，可用绝对路径消除启动目录差异。

另一终端启动前端：

~~~powershell
cd frontend
npm ci
npm run dev
~~~

访问 http://127.0.0.1:5173。Vite 默认代理到 http://127.0.0.1:8080；后端使用其他端口时，在 frontend/.env.local 设置 API_PROXY_TARGET。未配置 Kubernetes/S3 时，元数据维护仍可使用，相关远端操作会明确报错。

本地构建：

~~~powershell
mvn -f backend/pom.xml package
cd frontend
npm run build
npm run format:check
~~~

后端产物为 backend/target/flink-web-backend-0.1.0-SNAPSHOT.jar；前端产物为 frontend/dist。

## 3. 配置参数

配置默认值以 [application.yml](../backend/src/main/resources/application.yml) 为准。

| 环境变量 | 作用 / 默认值 |
| --- | --- |
| SERVER_PORT | 后端端口，8080 |
| FLINK_WEB_NAMESPACE | 固定作业 namespace，flink-jobs |
| FLINK_WEB_ADMIN_USERNAME / FLINK_WEB_ADMIN_PASSWORD | 初始管理员账号/密码；用户名默认 admin，密码至少 12 字符且 UTF-8 最多 72 字节，仅用户库为空时使用 |
| FLINK_WEB_MASTER_KEY | 必填，32 字节随机密钥的 Base64，独立备份 |
| FLINK_WEB_SECURE_COOKIE | HTTPS 入口设为 true；默认 false |
| FLINK_WEB_BES_BASEDIR | BES运行目录，默认java.io.tmpdir/flink-web-bes；须可写，容器默认/tmp/flink-web-bes |
| FLINK_WEB_STORAGE_TYPE | local（SQLite，默认）或 mysql |
| FLINK_WEB_LOCAL_PATH | 本地启动默认./data/flink-web.db；app镜像显式为/app/data/flink-web.db，覆盖值仍优先 |
| FLINK_WEB_STORAGE_INITIALIZE | 本地库不存在时是否允许新建；应用与 Kubernetes 清单均为 true；不存在时建库，已有库不重建；可显式设 false 禁止创建缺失文件 |
| FLINK_WEB_MYSQL_URL / FLINK_WEB_MYSQL_USERNAME / FLINK_WEB_MYSQL_PASSWORD | MySQL JDBC 连接与专用账号 |
| FLINK_WEB_K8S_URL | Kubernetes API，默认 https://kubernetes.default.svc |
| FLINK_WEB_K8S_TOKEN_FILE / FLINK_WEB_K8S_CA_FILE | 默认 ServiceAccount token 与 ca.crt 挂载路径 |
| FLINK_WEB_OPERATOR_NAMESPACE | Operator 所在 namespace，默认同作业 namespace |
| FLINK_WEB_OPERATOR_DEPLOYMENT | 查询的 Deployment，默认 flink-kubernetes-operator |
| FLINK_WEB_OPERATOR_CONTAINER | 取镜像版本的容器名，默认 flink-kubernetes-operator |
| FLINK_WEB_WORKLOAD_SA | 2026-10-12 起不再读取；作业/Session 表单默认填写 `{namespace}-flink`，用户可以修改，后端不替换显式账号 |
| FLINK_WEB_STATE_ROOT | 页面 S3 状态目录建议值，默认 s3://flink-state；不再强制写入作业 |
| FLINK_WEB_S3_BUCKET / FLINK_WEB_S3_ENDPOINT | 制品桶及可选 S3 兼容端点 |
| FLINK_WEB_S3_PREFIX | 平台作业/共享文件管理前缀，默认 artifacts/；全部文件从桶根目录浏览 |
| AWS_REGION | 默认 us-east-1 |
| AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY | 平台 S3 SDK 默认凭证链可读取；启动时成对加密导入配置管理 s3-access-key / s3-secret-key，仅初始化缺失项，不覆盖已保存值 |
| FLINK_WEB_DOWNLOADER_IMAGE | 统一下载器绑定的初始值，默认空；系统设置保存的单例绑定优先 |
| FLINK_WEB_ARTIFACT_HOSTS | HTTP(S) 制品地址白名单，逗号分隔 host 或 host:port |
| FLINK_WEB_ARTIFACT_CREDENTIALS_SECRET | 可选已有下载器 S3 Secret，使用 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 键；未配置则引用配置管理的 s3-access-key / s3-secret-key |
| FLINK_WEB_SQL_VALIDATOR_DIR | Parser 根目录；本地默认 ./sql-validators，后端镜像内 /opt/sql-validators |

主密钥不能随重启重新生成；数据库中只有加密值，丢失主密钥无法恢复原密钥配置。

## 4. Kubernetes 与容器部署

2026-10-01：后端、前端和下载器的最终运行层统一使用 Kylin V10 SP3，默认 KYLIN_BASE=macrosan/kylin:v10-sp3-2403（[MacroSAN 维护的公共镜像](https://hub.docker.com/r/macrosan/kylin)，并非麒麟官方仓库）。Dockerfile 支持通过 --build-arg KYLIN_BASE=内网仓库/镜像:SP3或更高版本 替换，基础镜像需提供兼容的 yum 软件源。2026-10-09：SQL 不再要求单独构建 Runner 运行镜像，使用与 JAR 相同的已登记 Flink 镜像及挂载 JAR。

构建阶段仍使用 Maven/Node 工具镜像；后端运行层复制 Temurin 17 JRE。前端使用 Kylin 软件源的 Nginx，以非 root 身份监听 8080，临时文件写 /tmp。下载器在同一 Kylin 基础层编译 Python 3.12.15（[源码及校验和](https://www.python.org/downloads/release/python-31215/)），安装固定 boto3 依赖，避免使用 SP3 软件源中较旧的 Python 3.7；替换 Python 版本时须同步校验和。构建需要能够访问软件源，或准备相应内网镜像源。

从项目根目录构建镜像，再按部署流程推送：

当前发布约定（2026-10-21）：每次代码修改校验后发布受影响镜像并核对仓库。用户本次要求升级并清理旧版本，三组件统一`192.168.68.1:5000/flink-web/{组件}:0.0.8`，替代0.0.7，不更新latest。新版本已发布，旧标签和历史manifest已清；[当前摘要及验证](#ai-一致性修复与版本发布2026-10-21)。发布镜像不自动更新实际K8s。

2026-10-09 Operator 与日志兼容说明：版本识别支持 `1.9.0-xxxxx`、`1.9.1-xxxx` 等发行前缀。FlinkStateSnapshot 从 [Operator 1.10](https://flink.apache.org/2024/10/25/apache-flink-kubernetes-operator-1.10.0-release-announcement/) 引入，按实际 CRD 可用性处理，不单凭镜像版本假设存在。列表中部署与快照请求独立处理错误，快照 CRD 缺失兼容旧版状态，快照读取失败仅显示局部提示，已有作业状态与 YAML 仍返回；Savepoint 停止继续要求有效完成凭据，读取失败不直接删除作业。

Pod 抽屉与详情页日志按需读取最新 10000 行，支持初始化/运行容器及上一实例，流式保留最后 4 MiB，总传输上限 64 MiB；依据 [Kubernetes Pod 日志接口](https://kubernetes.io/docs/reference/kubernetes-api/workload-resources/pod-v1/#read-log-log)。日志高亮时间戳与 ERROR/WARN/INFO 等级，支持行号与复制，并继续验证资源所有者链和 Secret 脱敏。需要已有 `pods/log` get、Pod 和所有者资源 get/list、日志脱敏所需 Secret get 授权。Kubernetes 401、403 与网络错误及平台授权失败在后端日志记录操作、资源路径、状态/错误码和必要身份，排查时区分平台角色/作业范围、CSRF 与 ServiceAccount RBAC；不输出请求正文、查询参数、令牌或上游错误正文。

~~~sh
docker build -f backend/Dockerfile -t 192.168.68.1:5000/flink-web/app:0.0.8 .
docker build -t 192.168.68.1:5000/flink-web/web:0.0.8 frontend
docker build -t 192.168.68.1:5000/flink-web/downloader:0.0.8 downloader
~~~

2026-10-05 发布验证：Docker Desktop linux/amd64 下 app、web、downloader 三个 Kylin SP3 镜像已重新构建并推送到 192.168.68.1:5000/flink-web，按用户要求覆盖 0.0.4 标签，不更新 latest，仓库摘要与本地一致。后端使用临时 SQLite 健康检查 UP，前端 HTTP 200、Nginx 配置及接口代理通过；下载器以非 root、只读根文件系统准备启动脚本；实际 Flink 1.20.5 主容器从自身镜像复制 Presto 插件并启动，/overview 返回 200。本地 MinIO 整目录复制与删除验证通过。已清理本次临时验证容器和网络，推送后清空 Docker 构建缓存，回收 2.505 GB，保留已标记镜像。未应用实际 Kubernetes 清单或修改运行作业；现场 Metaspace 故障需按下方说明调整作业内存并重新部署。SQL Runner 本次未发布。

2026-10-08 发布更新：Savepoint 停止修复已覆盖推送 app:0.0.4；本次页面与 DNS 启动修复覆盖推送 web:0.0.4（linux/amd64、Kylin SP3），下载器保持 2026-10-05 版本。仓库摘要已实际核对。后端 121 项、浏览器 48 项、SQL Runner 5 项、下载器 7 项测试全部通过，包含隔离 MinIO 目录生命周期测试；12 个页面在 1440px、900px 折叠导航及 390px 下未发现页面宽度溢出或未捕获脚本异常。前端镜像以非 root 和只读根文件系统运行，通过模拟 Kubernetes DNS search 的容器代理检查，验证后端缺失启动、补全 Service 域名、后端上线及变更 IP 后恢复。临时测试容器、服务与测试登录会话已清理，推送后清空构建缓存。现场 test2 已完成启停、快照和保存点恢复验证并删除，test5-copy 未改动。本次未更新前端 Kubernetes 工作负载，重建现有前端 Pod 后新页面才会生效。

2026-10-09：修复 Web 启动参数与 Kubernetes Service 环境变量重名导致的退出。已重新推送 web:0.0.4 并核对仓库摘要；app/downloader 未重新发布。真实容器回归通过 Service 变量注入、独立参数覆盖、非法端口拒绝、无后端启动与后端 IP 变更恢复；推送后清空构建缓存。尚未在现场更新工作负载。沿用 0.0.4 标签时，前后端容器必须显式使用 imagePullPolicy: Always，避免重启继续使用节点缓存。前端清单增加 enableServiceLinks: false；已有部署可用 [前端运行补丁](../deploy/kubernetes/frontend-runtime-patch.yaml) 通过 kubectl patch --type=strategic --patch-file 应用，补丁修改 Pod 模板会触发滚动更新。修复镜像本身也兼容启用 Service 环境变量的旧清单。

2026-10-09 SQL/JAR/Session 功能发布：app:0.0.4、web:0.0.4 已覆盖推送到上述仓库，不更新 latest；下载器保持现有版本。两份镜像均为 linux/amd64、Kylin SP3、UID 10001。后端构建阶段遇到 Maven Central TLS 握手失败，改用本地已验证依赖离线重新打包后端及 Parser，再按原 Dockerfile 构建相同运行层；未调整依赖版本。新镜像在只读根文件系统下通过临时 SQLite 健康检查、真实前后端加密登录、SQL/JAR/Session 页面和新增接口检查；Web DNS 与启动参数回归通过。推送后已实际核对仓库摘要与本地一致，临时容器和网络已清理。此次仅发布平台镜像，未更新 Kubernetes 工作负载或生产作业；Runner 仍按第 6 节上传到制品桶并挂载，不发布独立 SQL 镜像。

本次推送后清空 Docker 构建缓存，回收 702.7 MB，保留已标记镜像。

2026-10-09 Session 表单与草稿删除界面发布：已重新构建并覆盖推送 web:0.0.4，包含 Session 新建/配置布局、移除 Metaspace 独立字段，以及 JAR/SQL 草稿列表删除入口。前端镜像为 linux/amd64、Kylin SP3、UID 10001，实际容器通过只读根文件系统启动、与现有后端加密登录、新 Session 表单、JAR/SQL 页面及 DNS/接口代理检查。远端摘要与本地一致；后端运行代码和下载器未改动，保留此前镜像。临时容器及网络已清理，构建缓存回收 301 MB。未更新 Kubernetes 工作负载，已有前端 Pod 需重新拉取镜像后生效。

2026-10-09 Operator/快照兼容与 Pod 日志发布：已重新构建、验证并覆盖推送 app:0.0.4、web:0.0.4，不更新 latest，下载器保持原版本。后端与 Parser 使用本地已验证依赖离线打包，后端镜像沿用原 Dockerfile 的相同 Kylin 运行层；Web 按原 Dockerfile 构建。两份镜像均为 linux/amd64、Kylin SP3、UID 10001，在只读根文件系统下通过临时 SQLite 健康检查、实际加密登录和代理回归；实际镜像上的 5 项 Pod/日志浏览器检查通过。后端全量 146 项检查中 145 项通过，隔离 MinIO 用例本轮未启用，精确版本 Parser 已实际运行；27 项受影响浏览器回归、生产构建/类型检查、全源格式、Java 分层/中文注释和补丁检查通过。版本后缀识别、快照 CRD 缺失/403/网络失败、日志末尾与 UTF-8、资源归属/脱敏、高亮/复制、旧请求防回填及桌面/390px 布局均有覆盖。

已通过仓库 manifest 正文 SHA-256、Docker-Content-Digest 和 config 镜像 ID 核对本地与远端一致；临时容器、网络和本地测试服务已清理，推送后清空构建缓存，回收 703.6 MB，保留已标记镜像。本轮未更新实际 Kubernetes 工作负载，未对生产作业或现场 Operator 进行验收；已有 Pod 需按授权流程重新拉取镜像后生效。

| 历史镜像（标签 0.0.4；含本轮切换版本前的 app 过渡发布） | 仓库摘要 |
| --- | --- |
| 192.168.68.1:5000/flink-web/app | sha256:49f0d1ab6d7147782c05419ad1808dc58c573eded44d7f5c122115aa511bf995 |
| 192.168.68.1:5000/flink-web/downloader | sha256:afa85bd44748ee4ff033fad254aa8f7c8281bb3c8171032cc6334909d582f03a |
| 192.168.68.1:5000/flink-web/web | sha256:79c773658af815e4968d82544d21c9f78b041f2f3050d804d6ef7f730c5fd35a |

2026-10-15 AI 健壮性/日志缓冲发布：app/web:0.0.4 已覆盖推送，不更新 latest/downloader。复用已发布 Kylin/JRE/Nginx 层，仅替换本轮完整验证的后端 JAR 和前端 dist；无数据库迁移或新增密钥。最终 app config 为 sha256:2de2377bbea1c4e631b3a5576ec219ea88b143d858e0f1489023944f64ff4248，web config 为 sha256:a773b337bb5ebdcf54d6520ba8189ef61a5343c2a26834cd542070726b5c7d48。经现有 registry 的 localhost:15000 映射推送，再从指定 192.168.68.1:5000 核对 manifest 正文/响应摘要/config ID；远端 app20层/web18层的大小、压缩摘要和解压 diff ID 均与最终已测试镜像一致。

完整后端 224 项中 223 通过、可选 MinIO 跳过；51 项相关前端回归及最终镜像另 10 项真实 API 页面通过。实际容器中创建响应丢失后前端查询原编号恢复，协议模型仅调用一次；异常定义拒绝、加密登录/密钥保存、合法草稿及模式失效通过。SQL/Session 尾部和取消边界回归保留，小日志实测分配显著减少；不将合成结果外推为生产吞吐。详见 [本轮验收](agent/validation.md#ai-健壮性与按需日志缓冲2026-10-15)。平台 Pod 按已有授权流程重新拉取 app/web 生效，Runner/Parser/固定 Session 日志配置均无需替换；未更新实际 Kubernetes 工作负载，模型和 Kubernetes 使用隔离协议替身。

2026-10-14 AI 第一阶段发布：app/web:0.0.4 已覆盖推送，downloader 和 latest 未改。复用已验证的 Kylin/JRE/Nginx 运行层，替换完整校验后的后端 JAR/前端 dist，依赖、Runner 和两份精确 Parser 未改；linux/amd64、UID/GID10001。最终 app config 为 sha256:5effe20c9b695ff74db47ab7777a87dbada19c36bfb7ed70ed66395d16ed23f4，web config 为 sha256:6455ddfc65ea6a2d52cc4992b3eddf2027e1bba146cbd2e623cbd41799a531b7。通过现有 registry 的 localhost:15000 映射推送，再从指定 192.168.68.1:5000 核对 manifest 正文/响应摘要、config ID；远端 app19层/web16层的压缩 SHA-256、大小和解压 diff ID 均与测试镜像一致。

使用：管理员进入「系统设置→模型服务」，添加名称、实际模型标识、Base URL（如服务前缀 `/v1`）和可选 API Key，选择用途并测试；程序追加 `/chat/completions`，服务需支持非流式 Chat Completions 及 JSON 内容响应。API Key 通过一次性 config-secret 信封传输，以既有主密钥加密保存，不回显；留空保留，显式清除另选。私有模型可由管理员允许内网/本机和 HTTP，链路本地/元数据地址仍拒绝；公网使用 HTTPS，重定向不跟随。外部模型每次调用都需用户允许发送脱敏上下文。备份仍须同时保留原数据库和原加密主密钥，没有新增独立密钥或数据库迁移。

本次临时验证资源、检查标签、独占打包/日志与凭据占位已清理，六项本次私有构建缓存回收约82.5MB；保留最终已标记镜像、原数据库/备份、已有registry和共享依赖。

SQL 侧栏与作业报错「AI 分析」启用后可选上述模型；AI 不直接操作集群或自动应用 SQL。后端212项中211通过、可选MinIO跳过，52项相关页面及3项真实凭据页面通过；最终镜像8项链路、Web代理/DNS恢复检查通过。模型和 Kubernetes 为本地隔离协议替身，未认证真实厂商效果或现场运行环境。未更新实际 Kubernetes 工作负载；现有平台 Pod 按授权流程重新拉取 app/web 后生效，无需更新 Runner/Parser。详细结果见 [AI 验收](agent/validation.md#ai-助手第一阶段2026-10-14)。

2026-10-14 SQL 采集并发/共享编辑发布：app/web:0.0.4 已覆盖发布，downloader 保持上述摘要，不更新 latest。复用既有 Kylin/JRE/Nginx 运行层，仅替换完整校验后的后端 JAR 和前端 dist，Runner/两份 Parser 未修改。最终 app config 为 sha256:8a58ee8ca61ebbce8b39445aa3910d726fd61d9ab3f7835e6209ffa3fa4738dd，web config 为 sha256:7e1376e7f4d4982eadbac3ddf0c6b314c71f6db6a391fb4a1dcfe9cd679c4f99。沿用仓库直连 V2 发布，manifest 正文/响应摘要/config ID 及远端 18/15 层的压缩摘要、解压 diff ID 均与最终已测试镜像一致。

完整后端 201 项中 200 通过、MinIO 未启用；34 项受影响页面/逻辑及最终镜像 10 项真实 API 页面通过。最终镜像为 linux/amd64、UID/GID10001，只读根文件系统与临时 SQLite、加密登录、Session/SQL 无损往返通过。与隔离真实 Flink1.20.5/JDK17 联调，四种变更/中文多行、固定命名日志、后台自动取消和精确 1000 条输出通过，等待取消期间没有平台状态请求。Kubernetes 为协议替身，未更新现场工作负载或验收真实 Operator/RBAC/其他版本。详细结果和提交合并见 [本轮验收](agent/validation.md#sql-采集并发与共享配置编辑2026-10-14)。升级只需按已有授权流程重新拉取 app/web；本次不要求替换 Runner 或改 Session 日志配置。

2026-10-13 Session Pod/SQL 日志验证最终发布：app/web:0.0.4 已覆盖发布，当时 app manifest 为 sha256:55006a81fce04fce6ed9ece2f9f123c51ad99eb14184f6fc40c30c04a48240f7、web 为 sha256:34a00d26be39541712f974c7bee6b60980469c6838743b0f3ceb70dc1d6a7a0b，已由 10-14 发布替代；downloader 未改、不更新 latest。复用上一版已验证的 Kylin/JRE/Nginx 运行层，替换本轮离线校验的后端 JAR、新 Runner 和前端产物，保留两份已认证 Parser。最终 app config 为 sha256:4d0bd084c79f93eab763efa3f7d35d89bb66fd8301b70d91aa007885161f3ad8，web config 为 sha256:ff9ea4522460e63c66f27ccd57dec7f0d4b1fd1ce23e2b0c56a50cd7259e6153；通过指定仓库直连 V2 发布，核对 manifest 正文/响应摘要/config ID，并分别核对远端 17/13 层解压内容与本地已测试 rootfs diff ID 一致。

本轮完整 194 项后端中 193 项通过、MinIO 未启用；Runner 8 项、JDK8/17 Runner/Parser、39 项页面回归通过。最终非 root/只读镜像加密登录、Session Pod/逐行刷新/配置和 Runner 哈希检查通过；真实 Flink1.20.5 Session 固定文件中四种 RowKind、中文多行、1000 行边界和取消确认通过，平台后台取消另由集成测试核对。收尾缓存/文案修正后受影响范围及最终镜像重新校验，详见 [本轮验收](agent/validation.md#session-pod-与-sql-固定日志验证2026-10-13)。

应用此更新仍需按授权重新拉取平台 Pod；本轮未更新实际 Kubernetes 工作负载。既有验证 Session 必须停止再启动以应用平台固定 logConfiguration，旧 Runner 必须下载、上传并显式选择新文件，不能自动替换旧作业。固定日志位于每个 TM 的 /opt/flink/log/sql-validation.log，32MiB/一个副本；该路径须可写并与 Flink REST 日志目录一致。接口/日志配置依据 [Operator1.14日志说明](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-release-1.14/docs/operations/metrics-logging/) 与 [Flink1.20 REST](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/ops/rest_api/)。单后端实例限制继续有效，其他版本/现场 RBAC 与真实 Operator 调度仍需单独验收。

2026-10-13 Session/SQL 配置编辑发布：app/web 的 0.0.4 已覆盖发布，不更新 latest，downloader 保持上述摘要。后端及两份精确 Parser 使用既有离线依赖构建；镜像沿用已发布的 Kylin SP3/JRE/Nginx 运行层并替换本轮产物，Web 清除旧静态资源。最终镜像在 linux/amd64、UID 10001、只读根文件系统下完成临时 SQLite 健康检查、真实加密登录、Session/SQL 配置 API 与页面双向编辑、数值规范化 dry-run、PARSER 及 Web 代理/DNS 恢复验证。SQLite 验证容器的 /tmp 使用可执行 tmpfs 供本地库加载。

本机 Docker 代理连接失败，目标仓库直连正常；本轮通过该仓库标准 V2 接口上传镜像，核对 manifest 正文 SHA-256、Docker-Content-Digest 和 config 镜像 ID，逐层核对远端解压内容与已测试本地镜像的 rootfs diff ID 一致。全局代理配置未修改。187 项后端测试中 186 项通过，独立 MinIO 用例未启用；37 项受影响页面回归、JDK8/17 两版本 Parser 各八种输入、生产构建、格式/架构检查通过。Kubernetes 使用隔离协议替身，本轮未连接现场 Operator/CRD，也未更新实际工作负载；配置固定副本及已注册数量不能当作实际 Pod 副本验收。

本任务独占测试容器、网络、检查镜像标签和临时目录已清理；按已核实的缓存 ID 回收本任务私有构建缓存 134.6 MB，保留共享缓存、既有数据库/备份及已发布标签。


本次复用 `0.0.4` 标签。主清单已给 app/web 设置 `imagePullPolicy: Always`；应用清单后仍需重建已有 Pod 才会拉取。新生成的下载器同样使用 Always，已有作业须重新提交配置并重建 Pod；也可以在系统设置中绑定 `192.168.68.1:5000/flink-web/downloader@sha256:afa85bd44748ee4ff033fad254aa8f7c8281bb3c8171032cc6334909d582f03a`。已有运行中的作业不会随仓库标签更新自动变更。

2026-10-12 账号与 PodTemplate 发布：已构建、验证并覆盖发布 app/web 的 0.0.4 标签，摘要见上表；下载器沿用原版本，未更新 latest。后端使用全量测试通过的本地离线产物和已验证 Parser 产物，运行阶段与原 Dockerfile 相同；前端按原 Dockerfile 构建。两份镜像均为 linux/amd64、Kylin SP3、UID 10001。Docker 配置的 127.0.0.1:7800 代理不可用，因此通过同一个 registry 的 localhost:15000 映射入口推送；随后从指定的 192.168.68.1:5000 地址实际核对 manifest 正文 SHA-256、Docker-Content-Digest 与本地 config 镜像 ID，一致。

校验结果：后端 167 项全部通过，包含真实精确版本 Parser、隔离 MinIO、三处模板的独立保留/编辑/清空、显式账号保留及角色挂载冲突。前端类型检查、生产构建、全源格式、Java 分层/中文注释和补丁检查通过；实际新 Web 镜像上 20 项受影响浏览器回归通过，桌面与 390px 模板/搜索面板截图已检查。实际 app/web 在非 root、只读根文件系统下通过临时 SQLite 健康检查、真实加密登录、代理、作业 YAML 生成/验证/草稿保存与 Session 账号/模板回填；Web DNS/代理恢复回归通过。验证仅使用隔离测试数据和接口替身，未更新实际 Kubernetes 工作负载，未验收现场 Operator/Flink Pod 调度或用户填写账号的 RBAC。

运行账号由 JAR、SQL、Session 表单显式填写，前端默认 `{namespace}-flink`，既有 YAML/模板账号保留；后端不再读取 FLINK_WEB_WORKLOAD_SA 或补固定账号。请在目标 namespace 预先创建所选账号并授予 Flink 所需权限。共享及 JM/TM PodTemplate 分别保留，由 Operator 合并；平台托管下载器、插件或 AWS 环境变量与角色覆盖共存时启用 `kubernetes.operator.pod-template.merge-arrays-by-name=true`，显式关闭会报错，以防按位置合并覆盖容器/挂载。依据 [Operator 1.9 模板说明](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-release-1.9/zh/docs/custom-resource/pod-template/)。

本次临时容器、网络、本地页面服务、测试数据库/凭据和离线打包副本已清理；构建缓存回收 817.6 MB，本机推送入口的临时镜像标签已移除，保留指定仓库的已标记镜像及校验记录。

部署步骤：

1. 修改 [application.yaml](../deploy/kubernetes/application.yaml) 中的 namespace、后端/前端镜像、PVC 存储类及容量、状态目录、Operator 查询目标；在系统设置中统一绑定已推送的下载器镜像完整地址；首次未保存时使用 FLINK_WEB_DOWNLOADER_IMAGE，清单初始值为 192.168.68.1:5000/flink-web/downloader:0.0.4。
2. 填写 application.yaml 中已合并的 flink-web-secrets（管理员密码与主密钥）。已有环境必须填回原主密钥；MinIO 凭据复用同 namespace 的 minio-secret。
3. 核对 application.yaml 所选 ServiceAccount 的 RBAC 后直接应用该文件；数据库不存在时自动创建，已有文件保留，无需额外初始化补丁或部署后的开关切换。当前文件使用已有 flink-operator 身份；若改用平台独立 flink-web 身份，先应用 [rbac.yaml](../deploy/kubernetes/rbac.yaml)。
4. Operator 在其他 namespace 时，修改并应用 [operator-rbac.yaml](../deploy/kubernetes/operator-rbac.yaml)；设置对应 FLINK_WEB_OPERATOR_NAMESPACE。Deployment 名变更时同步修改 Role 的 resourceNames。
5. 访问 http://NODE_IP:30080，在系统设置手动核对 Operator 版本、就绪副本和 Pod，再登记镜像、模板和配置，并在对象浏览器上传依赖文件。

### 从原前后端 Deployment 迁移（2026-10-09）

迁移会短暂停止平台访问，Flink 作业由 Operator 独立管理。先备份数据库及原加密主密钥，确认原 PVC flink-web-data 已 Bound，清单使用相同 namespace、PVC、ServiceAccount 及 Secret。保留原 Deployment/Service 配置用于回退。已有环境填回平台 Secret 原值，或从清单移除同名 Secret 段并继续引用现有 Secret，避免占位值覆盖。下面只停止两个平台 Deployment，不删除 PVC、Secret、MinIO、Operator 或运行中的 FlinkDeployment。

~~~bash
# 任一步失败均停止，避免旧实例未退出时继续创建新实例。
set -e
# 在配置了实际集群上下文的终端，先验证清单；失败时不要执行后续步骤。
kubectl apply --dry-run=server -f deploy/kubernetes/application.yaml
# 停止旧平台并等待旧 Pod 真正退出，避免同时访问 SQLite。
kubectl -n flink-kubernetes-operator scale deployment/flink-web-backend deployment/flink-web-frontend --replicas=0
for app in flink-web-backend flink-web-frontend; do
  pods=$(kubectl -n flink-kubernetes-operator get pod -l "app=$app" -o name)
  if [ -n "$pods" ]; then
    kubectl -n flink-kubernetes-operator wait --for=delete $pods --timeout=180s
  fi
done
kubectl apply -f deploy/kubernetes/application.yaml
kubectl -n flink-kubernetes-operator rollout status statefulset/flink-web --timeout=180s
kubectl -n flink-kubernetes-operator get pod flink-web-0
# 确认 READY 为 2/2、原账号/作业/配置和页面正常后，删除两个已缩容的旧控制器。
kubectl -n flink-kubernetes-operator delete deployment flink-web-backend flink-web-frontend
~~~

后续沿用镜像标签时，使用 `kubectl -n flink-kubernetes-operator rollout restart statefulset/flink-web` 拉取已发布镜像，再检查 rollout status；单副本更新期间会短暂不可用。迁移失败时先缩容 StatefulSet 至 0 并等待其 Pod 退出，再按保留的旧配置恢复两个 Deployment 和 Service selector，仍复用同一 PVC 和主密钥。不要同时运行新旧平台实例。

2026-10-13核实：当前 Pod 设置 automountServiceAccountToken=true，已删除手工 kube-api-access 卷与挂载，由 Kubernetes 自动提供 ServiceAccount 凭据，通常会挂载到 backend/frontend 两个容器。frontend 不注入平台 Secret，也不挂载 SQLite 数据。最初10-09迁移版本是禁用自动挂载、仅backend挂 projected 卷；不能将该历史权限边界当成当前清单。自动挂载不会创建RBAC，serviceAccountName必须对应实际获准身份。依据 [StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/) 和 [ServiceAccount自动挂载](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/) 文档。

10-09历史验证：当时清单语法及单 StatefulSet/双容器、Service selector、端口、探针、旧 PVC 和后端专用令牌挂载检查通过；使用现有 app/web 镜像共用 Docker 网络命名空间，健康检查、加密登录、Session/JAR/SQL 页面和 localhost 代理通过。当时未配置 Kubernetes 上下文，未执行集群 server dry-run、迁移或工作负载更新；这些结果不认证后来改用自动挂载的生产权限。本轮仅静态核对，实际PVC/调度及凭据仍须在目标集群检查。

按每次修改后的发布约定，已重新推送 app/web:0.0.4 并核对仓库摘要；本轮只有 Kubernetes 清单调整，运行镜像内容和第 4 节摘要不变。临时验证容器已清理。

### 当前环境：Secret、MinIO 与首次启动

当前仓库清单使用 namespace **flink-kubernetes-operator**，平台、MinIO Service 与 minio-secret 在同一 namespace；镜像仓库、local-storage、PVC 2Gi 和 NodePort 30080 保持环境原配置。部署前仍需核对实际集群；本地 YAML 存在不代表资源已创建。

envFrom.secretRef 只把**已经存在**的 Secret 的键值注入容器环境变量，不会创建 Secret，也不提供 Kubernetes API 权限。被引用的 Secret 必须与 Pod 同 namespace；修改环境变量 Secret 后需重新创建 Pod。[Kubernetes Secret 文档](https://kubernetes.io/docs/concepts/configuration/secret/)

| 配置或身份 | 用途 |
| --- | --- |
| flink-web-secrets / FLINK_WEB_ADMIN_PASSWORD | 首次创建平台管理员的密码，至少 12 字符、UTF-8 最多 72 字节；库已有用户时不覆盖账号 |
| flink-web-secrets / FLINK_WEB_MASTER_KEY | 加密数据库中的配置密钥；32 字节随机数的 Base64。它不是登录密码、S3 密码或临时传输公钥，必须长期保存，恢复数据库时使用原值 |
| minio-secret / MINIO_ROOT_USER、MINIO_ROOT_PASSWORD | 现有 MinIO 凭据，通过 secretKeyRef 映射为 AWS_ACCESS_KEY_ID、AWS_SECRET_ACCESS_KEY；生产可替换为仅允许所需桶的专用账号 |
| StatefulSet Pod 的 ServiceAccount | 后端读取和修改 Kubernetes 资源所用的身份，与密码 Secret 无关；当前使用已有 flink-operator，自动挂载通常向两个容器提供凭据；rbac.yaml 提供可选的独立 flink-web 身份 |

2026-10-13同namespace配置补核：FLINK_WEB_NAMESPACE未设置时默认flink-jobs，不会自行读取凭据目录中的namespace文件。可通过env.valueFrom.fieldRef.fieldPath=metadata.namespace注入当前Pod namespace；FLINK_WEB_OPERATOR_NAMESPACE默认跟随它。只有实际Operator Deployment/容器名符合默认值时才能省略相应变量，单容器有选择回退。同namespace不等于所有资源都获准；平台读到Operator Pod仅证明该次请求可用。生产403须定位实际被拒绝的资源/操作和身份，不因共享Operator SA就替换账号或扩大授权。本聊天的403没有最终解决反馈，见 [待确认事项](agent/open-questions.md)。

**1. 填写同一清单中的平台 Secret。** application.yaml 已包含 flink-web-secrets，部署前填写 FLINK_WEB_ADMIN_PASSWORD 和 FLINK_WEB_MASTER_KEY。初始密码至少 12 字符、UTF-8 最多 72 字节，填写原始密码而非 Base64 或 fwenc1: 信封。新平台的主密钥可生成：

~~~bash
openssl rand -base64 32
~~~

把完整输出填入 FLINK_WEB_MASTER_KEY，主密钥独立备份；已有数据库必须填回原值，不能每次发布重新生成。保管含凭据的部署文件，不将实际值提交到版本库。主清单会同时创建或更新这个 Secret，不再需要单独创建步骤；已有 minio-secret 继续引用。

[secrets.example.yaml](../deploy/kubernetes/secrets.example.yaml) 仅供需要独立管理 Secret 时参考；使用独立方式时应从 application.yaml 移除同名 Secret 资源段，避免两处重复管理或空值覆盖。

**2. 核对已部署的 MinIO。** 同目录 [minio.yaml](../deploy/kubernetes/minio.yaml) 的 Service 位于 flink-kubernetes-operator，其 S3 API 为：

~~~yaml
- {name: FLINK_WEB_S3_ENDPOINT, value: "http://minio.flink-kubernetes-operator.svc.cluster.local:9000"}
- {name: FLINK_WEB_S3_BUCKET, value: "flink-web"}
- {name: AWS_REGION, value: "us-east-1"}
- {name: FLINK_WEB_STATE_ROOT, value: "s3://flink-state"}
~~~

变量名称必须为 ENDPOINT，不能少末尾 T；API 使用 9000，9001 是管理控制台。MinIO 已部署就无需重复部署；使用其控制台（当前清单 NodePort 30901）确认创建 **flink-web** 制品桶和 **flink-state** 状态桶，平台不会自动建桶。目录前缀 artifacts/ 按对象路径使用，无需预建文件夹。

这些配置只接通平台后端的 S3。Flink JM/TM 的 checkpoint、savepoint 还需要运行镜像中的 S3 插件及自身的 endpoint/凭据；下载 initContainer 也需独立配置，详见第 7 节。**SQLite 元数据存 PVC，S3 存 JAR、依赖和作业状态；S3 不能替代 /app/data/flink-web.db。**

**3. 一次应用，自动判断数据库文件。** application.yaml 中统一配置：

~~~yaml
- {name: FLINK_WEB_STORAGE_INITIALIZE, value: "true"}
~~~

/app/data/flink-web.db 不存在时，自动建库建表并按配置创建初始管理员；文件已存在时直接使用，保留账号、作业和配置数据，仅检查完整性并执行缺失的版本迁移。重启不重复创建管理员，也不重置密码。库文件损坏时仍报错，不删除文件重建。

~~~bash
kubectl -n flink-kubernetes-operator get secret minio-secret
kubectl -n flink-kubernetes-operator get svc minio
# 使用已有且具备平台所需权限的 ServiceAccount；当前清单为 flink-operator。
kubectl apply -f deploy/kubernetes/application.yaml
kubectl -n flink-kubernetes-operator rollout status statefulset/flink-web --timeout=180s
~~~

无需额外 patch，不需要启动后改回 false，也不删除 PVC 或重建 MinIO。原报错 Local database missing 对应旧清单的 INITIALIZE=false，应用更新后的主清单即可允许缺失库自动创建。该开关也可由运维显式设置 false，此时缺失文件仍会被拒绝。

本地 PV 需要为 UID/GID 10001 提供读写和可靠文件锁；PVC 未 Bound 或目录不可写需修复存储配置。自动建库不能恢复丢失的数据，已有环境仍应使用原 PVC、原库文件和原主密钥。平台和 Operator 同 namespace 时无需 operator-rbac.yaml；Operator 换 namespace 才按实际 ServiceAccount 应用跨 namespace 授权。

平台 RBAC 的主要范围：

| 资源 | 权限 |
| --- | --- |
| 作业 namespace 的 FlinkDeployment | get/list/create/update/delete |
| FlinkStateSnapshot | get/list；旧版不存在时使用 Deployment 内的快照信息 |
| Pods、pods/log、Events | get/list，用于显式诊断 |
| Deployment、ReplicaSet、StatefulSet | get，用于状态与所有权检查 |
| Secret | get/create，用于受管密钥及脱敏；仅使用运维既有引用时可评估移除 create |
| 单独配置的 Operator namespace | 指定 Deployment 的 get、Pods 的 list |

没有 watch 或集群级资源管理授权。跨 namespace 的 Operator 读取权限不会扩展作业管理范围；Flink 工作负载的 RBAC 仍由运维负责。

SQLite 必须保持单后端实例。当前单副本 StatefulSet 使用 RollingUpdate，更新时先终止旧 Pod 再启动替代 Pod；不要强制删除后并行启动第二实例，也不要让旧 Deployment 与 StatefulSet 同时挂载同一数据卷。仅保留旧 Deployment 部署方案时使用 Recreate，不能给 StatefulSet 配置该策略。PVC 需支持可靠的文件锁及 UID/GID 10001 写入。MySQL 模式可移除本地数据卷，但当前 Session 保存在进程内，后端仍按单副本部署。

本地容器体验：

~~~sh
# 将 deploy/.env.example 复制为 deploy/.env，填写独立凭据
docker compose --env-file deploy/.env -f deploy/docker-compose.yml up --build -d
~~~

该 Compose 映射前端到 http://localhost:8080，默认使用持久化卷，未挂载 Kubernetes 凭据；正式集群接入使用上面的清单。

### 凭据传输与 HTTPS

前后端须配套升级：登录 password、PUT /api/users/{username} 的非空 password、PUT /api/catalog/configs/{id} 中 kind=secret 的非空 value 均要求加密信封，明文返回 400 / INVALID_CREDENTIAL_ENVELOPE。现有 BCrypt 密码、配置密钥的落库加密和数据库结构不变，无需重置用户密码；编辑时留空继续保留原值。

浏览器通过统一请求层完成加密。自行编写 API 客户端时，保持同一个 Cookie 会话并按以下协议提交：

1. GET /api/auth/transport-key?purpose=用途&target=目标（参数须 URL 编码），返回 data 中的 id、algorithm、publicKey、purpose、target、expiresAt；响应禁止缓存。用途分别为 login、user-password、config-secret，目标为登录用户名、路径 username 或配置 id。
2. algorithm 固定 RSA-OAEP-256+A256GCM。publicKey 为 DER SubjectPublicKeyInfo 的 Base64；生成随机 32 字节 AES 密钥及 12 字节 IV，AES-256-GCM 加密 UTF-8 字段值，使用 128 位认证标签，AAD 为 UTF-8 的 id + 换行 + purpose + 换行 + target。
3. 使用公钥 RSA-OAEP 加密 AES 密钥，OAEP 摘要与 MGF1 摘要均为 SHA-256，label 为空。字段值为 fwenc1: 加 JSON 对象，四个字段为 id、key（封装密钥的 Base64）、iv（Base64）、data（密文后拼接认证标签的 Base64）。明文上限 64 KiB，账户密码仍受原有长度规则限制。
4. 获取 /api/auth/csrf，将返回的 headerName/token 加入写请求。登录仍使用表单编码，用户/配置写入仍使用 JSON。失败后重新获取挑战，由用户明确重试，不自动重放写请求。

挑战有效期 120 秒，每会话最多保留 8 个，绑定用途和目标；解密尝试会消费挑战，过期、篡改、跨会话或重复提交均拒绝。服务端 RSA 私钥仅在当前进程内，重启后需重新登录/提交。不要缓存公钥响应或把完整信封写入日志。浏览器使用系统安全随机数及 node-forge 标准算法实现，兼容 HTTP IP 入口没有 SubtleCrypto 的情形。

**字段加密不能保护 HTTP 下的页面、公钥和会话 Cookie，也不能阻止主动篡改，应通过 HTTPS 提供完整传输保护。** 当前清单仍为 HTTP，不会自动生成证书。可在已有前端 Nginx 终止 TLS：通过 Kubernetes Secret 挂载证书/私钥，将 frontend/nginx.conf 的 8080 监听改为 SSL 并配置证书路径，保持 Service 的 NodePort 与 targetPort 映射；客户端改用 https://IP:NodePort，证书须含访问 IP 的 SAN 并被浏览器信任。也可在现有网关终止 TLS。HTTPS 入口启用 FLINK_WEB_SECURE_COOKIE=true，前端 API 与 Flink UI 继续共用该入口。

实现依据：[node-forge 算法文档](https://github.com/digitalbazaar/forge)、[浏览器安全随机数说明](https://developer.mozilla.org/en-US/docs/Web/API/Crypto/getRandomValues)。

### 本机 Registry（2026-09-29）

Windows Docker Desktop 已部署 `registry:3`，容器名 `registry`，重启策略 `always`，数据挂载 `D:\DevTools\registry` → `/var/lib/registry`。用户要求仅供 `192.168.68.0/24` 访问，保持防火墙开启，不向其他网络开放。

当前容器仅发布 `127.0.0.1:15000:5000`，本机 API 已验证；此前推送、拉取和持久化验证通过，保留测试镜像 `registry:3`。Docker Desktop WSL 直接绑定 `192.168.68.1` 报 `cannot assign requested address`，因此使用 Windows 端口转发提供局域网入口 5000。

防火墙已创建 `DockerRegistry5000-VMnet8`：仅允许源 `192.168.68.0/24` 访问目标 `192.168.68.1:5000`，此前宽泛规则已撤销，各防火墙配置文件仍启用。Windows 已建立 `192.168.68.1:5000` → `127.0.0.1:15000` 的转发，并重载 IP Helper；本机通过该局域网地址访问 `/v2/` 返回 HTTP 200。尚未从另一台虚拟机或局域网设备验证访问。WSL 使用 mirrored 网络，同端口绑定冲突通过区分内部 15000 与局域网 5000 解决。可重用 `D:\DevTools\registry\enable-lan.ps1` 完成规则配置。

仓库为 HTTP、无认证。网段内 Docker 客户端需将 `192.168.68.1:5000` 合并到 Docker Engine 的 `insecure-registries` 并重启引擎，镜像地址格式为 `192.168.68.1:5000/镜像名:标签`。参见 [Registry HTTP 配置](https://distribution.github.io/distribution/about/insecure/)。

## 5. 数据库、迁移与备份

SQLite 使用单连接池、DELETE journal、FULL synchronous、实例文件锁及启动完整性检查。不要让多个进程或 Pod 同时使用同一库文件。

MySQL 模式设置 FLINK_WEB_STORAGE_TYPE=mysql，并提供 JDBC URL、用户名和密码，例如：
~~~text
jdbc:mysql://mysql:3306/flink_web?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC
~~~

先创建空数据库并授权。应用根据 platform_schema 执行版本化迁移：

| 版本 | 数据结构 |
| --- | --- |
| V1 | platform_schema、job_drafts |
| V2 | platform_records：用户、配置、模板、制品、SQL、审计等 JSON 元数据 |
| V3 | job_bindings：资源 UID 与平台作业绑定 |
| V4 | platform_record_subjects：操作、发布、SQL 版本与快照的主体索引 |
| V5 | job_numbers：数据库自增作业编号与固定资源名映射；为旧作业补号但不立即改动资源；旧资源删除后重建使用编号名，删除平台作业后保留编号 |

2026-09-30：前后端须配套更新。新草稿分配自增编号，部署名称使用 flink-0000001 形式的编号（至少七位，超过七位不截断）。历史资源纳管或数据库迁移时保持原名，先停止旧资源并确认删除，再启动时重建为编号名；平台展示名和 UUID 不变。停止可立即删除，也可先由 Operator 生成 Savepoint，用户手动刷新或再次停止时推进删除。停止意图和恢复路径落库，进程重启后可继续；不增加后台轮询。编号可能有间隔，不承诺连续。SQLite 迁移与编号重建由自动化测试及 Kubernetes 协议替身验证；MySQL 和真实 Operator 删除/恢复仍需部署环境验收。

全量 DDL 与增量脚本位于 [MySQL](../backend/src/main/resources/db/mysql/schema.sql) 和 [SQLite](../backend/src/main/resources/db/sqlite/schema.sql) 所在目录。已有数据库通过迁移升级，不要删除后重建；全量脚本的 CREATE IF NOT EXISTS 不能修复手工损坏的表。MySQL DDL 存在隐式提交，迁移失败应检查完成情况再恢复启动。

备份与切换：

- SQLite 停机后备份整个库文件，主密钥单独保管；恢复到可写 PVC 后使用原主密钥启动。
- MySQL 使用一致性备份，同时备份主密钥；恢复先验证迁移版本与用户登录。
- S3 制品、checkpoint/savepoint 与 Kubernetes Secret 不在元数据库备份内，分别备份。
- local/mysql 切换不会搬迁、双写或自动转换数据；需要独立迁移方案。GoldenDB 尚未适配。
- 当前无历史记录自动归档、主密钥自动轮换或旧 Secret 自动清理；规划长期容量与维护流程。

## 6. SQL Runner、Parser 与运行镜像

**Runner 运行作业，Parser 校验语法，两者不能互相替代。**

2026-10-12：Runner 默认使用 Flink 1.20.3 API 构建，Runner 和 Parser 的运行产物均为 Java 8 字节码（major 52），支持已实测的 JDK 8/17。使用 JDK 17 构建并运行测试，平台后端继续使用 JDK 17。Parser 的 Flink/Calcite 版本须精确匹配；Runner 已验证 1.20.3 API 和 1.20.5 运行镜像，其他组合仍需按实际版本构建验收，不作为 1.16–2.3 全版本认证：

~~~sh
mvn -f sql-runner/pom.xml -Dflink.version=1.20.3 package
~~~

2026-10-10：可把生成的 sql-runner/target/flink-web-sql-runner.jar 上传到平台制品桶管理前缀下的 **shared/sql-runner/{精确Flink版本}/flink-web-sql-runner.jar**，例如 artifacts/shared/sql-runner/1.20.3/flink-web-sql-runner.jar；SQL/Session 表单从 sql-runner 列表手动选择文件，不再根据镜像版本自动取默认文件。其他位置可确认具体 HTTP/HTTPS/S3 地址，使用前须核对 Runner 与运行镜像兼容性。旧 Dockerfile 仅保留历史兼容，不属于默认部署流程。

SQL 或验证专用 Session 的“SQL Runner 文件”区域现在提供“下载平台 SQL Runner”，通过受登录与管理员/开发者权限保护的 `/api/sql/runner/download` 下载当前平台产物。后端镜像从 `/opt/sql-runner/flink-web-sql-runner.jar` 提供文件；本地运行可用 `FLINK_WEB_SQL_RUNNER_JAR` 指定安装文件。下载后上传到 shared/sql-runner/1.20.5 等目录并重新选择，保存并重新发布 SQL；验证 Session 须停止后更新选择并重新启动。原 Runner 不自动覆盖，沿用旧文件仍会执行旧逻辑。

本轮 Runner 修复磁盘 `pipeline.jars`/`pipeline.classpaths` 中的 `local:///` 覆盖 Flink 提交上下文的问题。保持生成 YAML 中用于声明容器内文件的 `local:///`，在 Runner 中沿用 Flink 已解析/分发的 JAR 和类路径，保留业务、状态和 S3 配置。可用 `python scripts/check_sql_application.py --image Flink镜像 --runner Runner文件` 在隔离 Application 中执行初始 datagen → print SQL，脚本自动清理自己的容器及网络。

SQL Application 和 Session 均通过现有 fw-download initContainer 下载 Runner、依赖到共享 emptyDir，挂载 /opt/flink/usrlib，并使用与 JAR 作业相同的登记镜像。需配置系统下载器镜像、工作负载 S3 访问凭据；缺少 Runner 对象时本地渲染/预览即失败。运行镜像仍需提供 Table API、Planner、Table Runtime 与 Print，Connector/驱动可以作为附加 JAR 下载；文件系统插件继续按第 7 节独立准备。不能把核心 Flink JAR 混入 Connector 包。

### Session 验证准备（2026-10-09）

2026-10-09 表单优化：新建/配置 Session 使用与作业编辑相同的分区卡片、字段网格及保存操作栏，桌面双列、窄屏单列；保存后返回集群列表，仍需显式启动。2026-10-12 起三处 PodTemplate 使用默认折叠的 YAML 高亮编辑器，保存为对象并兼容已有 JSON 配置；JAR 页面移除 JM/TM Metaspace 独立输入，已有值保留，可通过 Flink 配置编辑器修改；不改变既有运行资源。

本轮界面和删除入口验收：30 项受影响浏览器回归、19 项后端 Session/草稿删除/存储生命周期检查通过，生产构建、全源格式及补丁空白检查通过；已核对桌面与 390px 页面截图。浏览器采用接口模拟，后端使用隔离 SQLite，未操作生产集群。本轮前端镜像已按用户要求发布，实际镜像检查及摘要见第 4 节。

1. 管理员在 Session 集群创建唯一的“SQL 验证专用 Session”，选择与 SQL 相同的 Flink 镜像，配置资源、PodTemplate/Secret 与需要的非 JAR 文件，保存并启动。资源为省略 spec.job 的 FlinkDeployment，使用固定作业 namespace 和现有 ServiceAccount；普通 Session 列表不混入 Application 作业管理。
2. 后端需要访问集群内 fw-session-sql-validation-rest.{namespace}.svc:8081。保留 Flink Web 的 JAR 上传、运行和取消能力；本功能不会把这些写接口暴露到已有只读 Flink UI 代理。网络策略需允许平台访问 Session REST。
3. 每次验证将 Runner 和所选 Connector JAR 从受管 S3 下载后打为临时用户 JAR，合并 META-INF/services，去掉原签名，拒绝冲突类。解压总量不超过 256 MiB、单条目不超过 16 MiB，最多 50 个依赖；通过 /jars/upload、/jars/{id}/run 分发到 JM/TM，结束清理上传副本。非 JAR 配置文件须已列入 Session 附加文件，环境变量须在集群 Pod 中准备；Flink 插件不能通过此包热加载。
4. 专用集群自动设 taskmanager.system-out.mode=LOG、web.refresh-interval=1000，把 Print 输出送入 Flink UI 的 TaskManager 日志文件。基础镜像需启用文件日志，日志不应只写外部采集器。平台仅展示带本次验证标识的数据，并脱敏错误；建议日志单文件轮转小于 128 MiB。Flink 1.20 忽略 HTTP Range，后端流式读取完整文件且仅保留最后 512 KiB，超限会提示调整日志或重启专用集群。
5. SQL 检查通过后点击 SQL 验证，浏览器每两秒监听状态/结果；后端两分钟截止后取消，取消未确认则重试清理。固定 JobID 防止未知提交被重试成多份任务；结果不落库，后端重启后历史行不恢复，仍恢复未完成任务清理。停止普通 Session 前需处理其运行作业，Operator 可能等待这些任务结束才能删除资源。

实际验证范围：默认 Flink 1.20.3 Runner 执行测试通过；在隔离 Docker Flink 1.20.5 Session 中，已验证 REST 上传/固定 JobID、动态文件系统 Connector 与 CSV 格式依赖、原 Sink 替换为 Print、100 条输出及超过 512 KiB 的日志响应。未在生产 Kubernetes 发布，也未对其他 Flink 补丁版本或所有 Connector 组合做运行认证。

本次验证记录（2026-10-09）：后端 140 项测试全部通过，包含实际精确版本 Parser 和隔离 MinIO 目录生命周期；Runner 7 项通过，浏览器 52 项通过，手机布局及提示修复后新增 4 项再次通过。前端生产构建、全源格式检查、后端分层/中文注释检查和补丁空白检查通过。验证只使用临时数据库、模拟 Kubernetes 和隔离 Docker Session，未改动生产作业；之后已按用户要求发布 app/web 镜像，摘要及镜像运行检查见第 4 节。可用 `python scripts/check_sql_session.py --image 已有Flink镜像 --runner Runner产物路径` 复查 Session 行为，脚本自动清理创建的测试容器和网络。

后端镜像已内置 Flink 1.20.3、1.20.5 / Calcite 1.32.0 Parser；本地安装目录为 sql-validators 下的对应精确版本。点击 SQL 检查时由平台自动调用，不将 validator.jar 上传为 SQL 主程序或 Connector。本地可重建安装：

~~~powershell
mvn -f sql-validator/pom.xml package
New-Item -ItemType Directory -Force sql-validators/1.20.3
Copy-Item sql-validator/target/flink-web-sql-validator.jar sql-validators/1.20.3/validator.jar
mvn -f sql-validator/pom.xml -Dflink.version=1.20.5 clean package
New-Item -ItemType Directory -Force sql-validators/1.20.5
Copy-Item sql-validator/target/flink-web-sql-validator.jar sql-validators/1.20.5/validator.jar
$env:FLINK_WEB_SQL_VALIDATOR_DIR = (Resolve-Path sql-validators).Path
~~~

镜像登记的 flinkVersion 必须为匹配的精确补丁版本，未安装对应适配器时返回 BASIC。其他版本需核对 Flink/Calcite 依赖后独立构建，不能只改目录名；以只读卷装入目录时不要遮蔽需保留的默认版本。每次校验使用受限独立 JVM，不继承平台数据库/S3 凭据，不加载用户连接器或探测 Catalog。

2026-10-12 JDK 验收：JDK 8u361 和 JDK 17.0.10 下，两份 Parser 各通过有效初始 SQL、错误 SQL、空数组、非文本语句、101 条及超限输入六种检查；Runner 使用真实 Flink 1.20.3 Planner/MiniCluster 各执行并输出三条数据。Parser JSON 固定 UTF-8，避免 Windows JDK 8 默认编码影响协议。两份 Parser 的非多版本目录依赖字节码一并检查不高于 major 52。`scripts/check_sql_jars.py` 可复测；需传入对应 Flink 运行依赖的 classpath 文件，不能将测试类或 JUnit 当作运行依赖。

本轮交付 SHA-256：Runner `b0ee60c2dc646aa95614d18c7c6101be1b57342c9da458b3137c49ff61952d69`；Parser 1.20.3 `a436aa7d84873afa44202979366355160a17c69ba87d03d8873736afda1882e1`；Parser 1.20.5 `df70333dcd526eb53a3d7dd67e5781680350017e8156b16edaf042ec13c613ac`。以上是本轮文件摘要，重建后以实际文件为准。

## 7. S3、下载器与状态存储

必须区分三种身份：

| 使用方 | 职责与配置 |
| --- | --- |
| 平台后端 | 制品上传/浏览/下载及快照路径检查，使用平台 S3 配置和凭据链 |
| initContainer 下载器 | 启动前获取制品，使用工作负载凭据或指定的下载器 Secret |
| Flink 作业 | checkpoint/savepoint、Catalog/文件系统访问，使用运行镜像的插件与 PodTemplate/工作负载身份 |

2026-10-12：平台启动时仍将成对 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 加密导入配置管理（s3-access-key / s3-secret-key），只初始化缺失项、不覆盖已保存值。Flink 状态存储的 s3.access-key / s3.secret-key 实际提交时直接写入 spec.flinkConfiguration，不再生成 Flink 主容器 AWS AK/SK 环境变量；共享/JM/TM 主容器旧同名变量在使用该配置时移除。既有 Kubernetes Secret 引用会按请求 GET 读取目标 namespace 的指定 data 键、解码后填入 Flink 配置，平台身份须有该 Secret 的 get 权限；缺失、无权限或非法值明确失败。普通容器 env.value 的显式 Secret 引用仍保留为 valueFrom。预览恢复源占位符或隐藏实际值，发布历史隐藏 S3 凭据，停止前配置加密保存；不新建额外 Secret。平台 S3 SDK 和下载 initContainer 的 AWS 环境凭据用途保留；运行镜像插件必须识别 Flink 的 s3.access-key / s3.secret-key 配置。

2026-10-01：initContainer 在共享 spec.podTemplate 中定义一次，由 Operator 应用于 JM/TM；不再校验下载文件 SHA-256，超时、大小超限或下载失败阻止启动。HTTP(S) 需在白名单内，禁止重定向，Bearer Token 必须走 HTTPS。

2026-10-10：S3 文件下载器按需生成 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY；配置管理的加密值实际提交时展开为环境变量，显式已有 Secret 引用仍保留。下载器注入配置的 AWS_REGION 用于 V4 签名，不生成 AWS_DEFAULT_REGION，也不在 Flink 配置中补 s3.region。即使作业关闭状态存储，S3 制品仍需要下载凭据；纯 HTTP 下载不添加 S3 变量。修改全局镜像后，预览及提交会更新旧 fw-download 镜像、清理历史校验字段和区域变量；运行中资源不自动更新。共享模板结构参考 [Operator 官方示例](https://github.com/apache/flink-kubernetes-operator/blob/main/examples/basic-checkpoint-ha.yaml)。

制品卷挂载 /opt/flink/usrlib，会遮蔽镜像原目录。依赖下载启用时，把所需用户 JAR 全部列入下载清单；预置主 JAR 可放在未被遮蔽的路径。Flink 核心、Planner 保留在运行镜像。Presto S3 插件由主容器启动脚本从自身镜像 opt 目录复制到独立插件卷，不作为普通 usrlib 依赖；不能同时手动将同一插件放入 lib/usrlib。

状态存储在 JAR/SQL 页面显式开启；启用后默认使用 S3，页面用 FLINK_WEB_STATE_ROOT 和作业 UUID 自动生成 checkpoint/savepoint 路径并写入 Flink 配置，同时写入连接参数和配置管理中的密钥引用；这些字段不再单独展示为表单输入，必要时可在 Flink 配置中调整。后台不再强制周期、保留数量或目录。Checkpoint 外部存储保留策略由页面选择：默认保留，也可选择取消后删除、不保留外部 Checkpoint 或 Flink 默认。立即删除 FlinkDeployment 可能来不及正常执行取消清理，需检查残留对象；Savepoint 的保留不受该策略影响。关闭存储会移除表单管理的 Checkpoint 相关配置。2026-10-04 起仅使用一个 downloader initContainer 下载程序并准备 /flink-bootstrap/start-flink.sh。主容器的 kubernetes.entry.path 指向此脚本，启动时从本镜像 /opt/flink/opt 自动发现唯一 flink-s3-fs-presto-*.jar，复制到可写 emptyDir（/opt/flink/plugins/s3-fs-presto/），再执行原入口并透传参数。无需在 S3 保存插件，也不依赖镜像 tag 中的补丁版本。镜像须提供 sh、cp、mkdir、rm 和默认 /docker-entrypoint.sh；自定义 kubernetes.entry.path 会保存在 FLINK_WEB_ORIGINAL_ENTRYPOINT 并继续调用，禁用后恢复。仅启用插件时不会遮蔽 /opt/flink/usrlib；主容器需要插件卷写权限。缺失/多个插件会在启动日志中明确报错。所有 S3 程序下载仍需工作负载凭据；平台 S3 SDK 可访问不表示 Flink 可以访问。入口配置依据 [Flink 1.20 Kubernetes 配置](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/config/#kubernetes-entry-path)。last-state 仅保留为升级策略，需要 Kubernetes HA 与 HA 存储，不再作为停止选项。指定路径的手动快照检查仍使用平台 S3 endpoint/凭据并限制到配置的桶，不证明恢复兼容性。

2026-10-02：删除平台作业会递归清理 FLINK_WEB_S3_PREFIX/jobs/{UUID}/ 与 FLINK_WEB_STATE_ROOT/{UUID}/；只对平台分配的 UUID 前缀执行删除，要求对象存储身份具备两个桶相应前缀的 ListBucket / DeleteObject 权限。未配置 S3 且草稿无 S3 引用时无需清理；有 S3 引用但未配置时拒绝删除。共享、自定义外部目录不自动删除。版本化桶的历史版本由生命周期策略管理。复制使用同桶 CopyObject（源 GetObject、目标 PutObject），带源 ETag 条件校验，分页复制专属文件，不复制状态数据。清理失败保留平台记录供重试，但已成功删除的对象无法恢复。

对象浏览器从制品桶根目录浏览，依赖快捷入口仍位于 FLINK_WEB_S3_PREFIX 下的 jobs/{作业 UUID}/ 和 shared/。上传直接写入 S3，拒绝同名覆盖；不需要制品登记。可创建目录标记、浏览、下载、删除具体文件，以及确认后递归删除选中的非根目录。目录删除包含标记、全部子目录和文件，仅作用于带末尾斜杠的精确前缀，禁止桶根、穿越路径和无分隔符前缀；失败可能已经删除部分对象，刷新后重试。S3 身份除 List/Get/Put 外，删除文件或目录需要对应前缀的 DeleteObject 权限，递归目录还需 ListBucket 权限；条件写入需要对象存储支持 If-None-Match。引用对象时仅 HEAD 查询存在性和大小，最大 1 GiB；不再为预览下载文件计算校验和。上传仍可记录内容摘要元数据，但不用于 initContainer 校验。保留旧登记记录用于已有下载清单兼容。


### 更换 S3 兼容服务（2026-10-10）

S3 服务可以替换为提供兼容接口的云存储或私有存储，平台不以 MinIO 的服务名识别提供商。设置 FLINK_WEB_S3_ENDPOINT、FLINK_WEB_S3_BUCKET、AWS_REGION 和对应 AK/SK；使用临时凭据时还需 AWS_SESSION_TOKEN。Compose 已传递 AWS_REGION，Kubernetes 清单中的当前 MinIO 端点只是部署示例，更换时应明确设置区域端点和凭据来源。

华为 OBS 示例：FLINK_WEB_S3_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com，AWS_REGION=cn-north-4，桶填写该区域已创建的桶。端点填写区域服务域名，不在端点前附加桶名，不使用控制台/静态网站地址。参数依据 [OBS V4 签名说明](https://support.huaweicloud.com/obs_faq/obs_faq_0274.html)。

后端使用 V4、路径式访问、禁用分块编码、仅在要求时计算/验证 SDK checksum，并启用 LegacyMd5Plugin 为批量删除等必要操作提供 Content-MD5；这是 [AWS SDK 官方的第三方兼容配置](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)。下载器显式使用 s3v4、配置的签名区域以及 WHEN_REQUIRED checksum 策略，保持 TLS 校验。OBS 的 [批量删除接口](https://support.huaweicloud.com/api-obs/obs_04_0086.html) 要求 Content-MD5/Content-Length，最多 1000 项，quiet 模式仍需检查单项错误。

接入端点需支持平台所用的 ListObjectsV2（含 delimiter/分页）、HeadObject、GetObject、PutObject、CopyObject、DeleteObject、DeleteObjects，以及上传的 If-None-Match 条件写入；若不支持条件写入，不能保证不覆盖同名对象，平台不静默降级。不同区域、存储版本、IAM 授权与网关行为仍需用目标环境验收。已有隔离 MinIO、HTTP 请求的 XML/MD5 匹配和 cn-north-4 签名区域下载测试记录；华为 OBS 全接口及全部运行路径尚未完成实测认证，但另有下面的用户现场成功反馈，不能笼统说从未在华为服务成功。

2026-10-10用户现场反馈（2026-10-13补录）：用户明确回复生产华为S3加入以下环境变量后恢复正常；没有单独提供Pod重建过程或日志。此反馈支持新版SDK默认校验与目标服务兼容性的判断，缺少原始错误码/请求、具体失败操作、华为服务版本及完整回归，不能确认为唯一根因或全部接口认证：

~~~yaml
- name: AWS_REQUEST_CHECKSUM_CALCULATION
  value: WHEN_REQUIRED
- name: AWS_RESPONSE_CHECKSUM_VALIDATION
  value: WHEN_REQUIRED
~~~

当前代码已在后端S3Client及下载器显式设置相同策略；环境变量修复记录主要用于旧镜像和部署复现，并非关闭所有校验或证明当前代码仍待修改。必要操作的校验仍保留。`artifacts/`无需预建，写入前缀下对象后自然显示，启动不自动创建空目录；桶本身必须已存在，平台不创建桶。日志诊断已由S3FailureDiagnostics输出操作、HTTP状态、S3错误码、请求ID及安全原因链；该聊天暂停时未测试/发布，现有实现是后续状态。本轮没有复测生产服务或重新发布镜像。

平台制品、initContainer 下载器和 Flink 状态存储仍是三条连接路径；Flink 运行镜像中的 S3 插件与目标存储也必须兼容。更换存储不会自动迁移已有制品、保存点、数据库或正在运行的作业。

切换时修改平台endpoint/bucket、必要的prefix/state-root/region及AWS凭据来源后重建平台Pod；同时更新配置管理中已经保存的s3-access-key/s3-secret-key，启动环境只初始化缺失项，不覆盖旧值。若配置FLINK_WEB_ARTIFACT_CREDENTIALS_SECRET，还须核对其引用的下载器凭据。已有作业/Session的连接与状态配置需显式更新部署，不能靠平台重启自动切换；跨存储先按状态保留策略安排保存点、对象迁移和恢复。Secret通过环境注入时更新后须重建使用它的容器。现场成功反馈和未覆盖范围见 [本聊天核对](agent/validation.md#sql-与-session-开发聊天归档核对2026-10-13)。

## 8. 排障与验收

| 现象 | 优先检查 |
| --- | --- |
| 初始管理员密码校验失败 | 修改 flink-web-secrets 的 FLINK_WEB_ADMIN_PASSWORD，至少 12 字符且 UTF-8 不超过 72 字节，再按当前清单重建 StatefulSet/flink-web 的 Pod；旧程序提示中的“后端 Deployment”不是当前资源名称。如已有用户却进入初始化，先核对 PVC/库路径 |
| INVALID_CREDENTIAL_ENVELOPE | 前后端是否同步升级、Cookie 会话是否一致、挑战是否过期或已消费；重新获取挑战，不改为明文重试 |
| 本地数据库启动被拒绝 | 路径、PVC 权限、实例锁、INITIALIZE 开关；不要通过开启初始化掩盖挂错卷 |
| JOB_NAME_EXISTS / DATA_CONFLICT | 作业重名或数据约束；修改名称或编辑已有记录 |
| STORAGE_BUSY / STORAGE_ERROR | 写入竞争、文件系统与数据库配置；查看安全日志中的 SQLState/vendorCode，不输出 YAML 或密钥 |
| REVISION_CONFLICT / 身份冲突 | 重新读取草稿及运行资源；核对 resourceVersion、绑定 UID 和同名资源是否被重建 |
| Operator 未找到/版本未知 | 查询 namespace、Deployment/容器名、镜像 tag 与 RBAC；支持 1.9.0-xxxxx 等版本前缀，任意自定义标签或未知发行系列不自动猜测 |
| Operator 有版本但无 Pod 状态 | 查看 podMessage、Pod list 权限、Deployment selector 与实际副本 |
| Kubernetes 拒绝或字段被裁剪 | CRD/Operator 版本、RBAC、字段支持、工作负载 SA；dry-run 成功也不代表作业运行成功 |
| SQL 只有 BASIC 检查 | 精确 flinkVersion 与 Parser 目录是否匹配 |
| SQL 运行失败 | Runner、Planner、Connector、Catalog 插件、字段语义和远端服务；从诊断页按需读取日志 |
| initContainer / S3 失败 | 统一下载器绑定、白名单、URI、文件名、Secret 引用、插件与卷遮蔽 |
| Flink UI 不可用 | 作业权限、REST Service 名和 8081 端口、代理前缀及版本兼容性 |
| 操作结果 UNKNOWN | 先手动查询，确认集群是否已接受请求，避免用新 requestId 盲目重复提交 |

2026-10-10 排障日志：所有涉及异常的 WARN 打印完整后端调用栈、cause 原因链及 suppressed 异常，包含授权、API 处理、Kubernetes、快照查询、运行操作、S3 与 Flink UI 代理路径。S3 批量删除的部分失败记录错误码、失败数量、目录及已完成批次的删除数量；S3 日志副本保留原异常每层类型说明、全部栈帧、cause 与 suppressed 关系，SDK 上游正文和签名凭据继续遮蔽。CSRF 令牌从异常消息中遮蔽；请求正文、安全令牌及 Kubernetes 错误正文仍不额外记录。上游只有 HTTP 错误响应时，日志提供状态及本地检测堆栈，远端根因仍需查看对应服务日志。

健康检查为 /actuator/health。页面写请求使用 Session + CSRF；元数据修改带 revision，运行操作带 UUID requestId。同一 requestId 具有幂等语义，不能用历史 ACCEPTED 推断当前状态。

上线时实际验证：登录/权限、既有作业只登记纳管、JAR 与 SQL 部署、3 秒刷新和 YAML 零额外请求、Operator 查询、停止与删除保护、真实 savepoint/checkpoint 恢复、同源 Flink UI，以及数据库/PVC 备份恢复。HTTP 协议替身和浏览器回归不能替代这些检查。Kubernetes 最低可用版本取决于运维安装的 Operator/CRD，不承诺所有 Kubernetes 1.20+ 组合均可运行。

## 9. 维护入口

~~~text
backend/        API、业务、权限、持久化、Kubernetes/S3 客户端
frontend/       Vue 业务页面、共享组件、Vite 与 Nginx
sql-runner/     Flink SQL Application/Session 验证入口 JAR
sql-validator/  按精确版本安装的独立语法校验器
downloader/     HTTP/S3 制品下载 initContainer
deploy/         Compose、RBAC、PVC、Deployment、NodePort
docs/           本指南与功能说明
~~~

后端按技术层再按功能分包：controller → service 接口 → repository 或 collector/client；实现位于对应 impl。元数据 SQL 位于 repository/datasource，显式 Java 类和方法保留中文用途注释。前端 app 负责路由/布局，modules 按业务组织，shared 放公共组件、API、样式和组合函数。

常用检查（在项目根目录运行；前端命令需切换目录）：

~~~powershell
mvn -f sql-validator/pom.xml package
mvn -f backend/pom.xml test
mvn -f sql-runner/pom.xml test
python scripts/check_backend.py --java "$env:JAVA_HOME/bin/java.exe"
cd frontend
npm ci
npm run build
npm run format:check
# 启动隔离的测试服务并设置 E2E_USERNAME / E2E_PASSWORD 后：
npm run test:e2e
~~~

浏览器默认访问 127.0.0.1:5173；可设 E2E_BASE_URL、E2E_BROWSER。端口诊断测试可用 E2E_API_PORT 指定后端监听端口；纳管用例需要 E2E_K8S_FIXTURE 指向 [本地 Kubernetes 替身](../scripts/mock_kubernetes.py)。不要以生产数据库/集群作为自动化测试目标。

功能、参数或行为变化时更新这两份文档相关段落，并按 [知识索引](agent/INDEX.md) 增量维护受影响模块/需求/状态；不另建按轮次命名的升级报告、聊天缓存或截图记录。版本筛选数据及出处维护在 [operator-compatibility.json](../backend/src/main/resources/operator-compatibility.json)，数据库结构以增量 SQL 为准。

2026-10-02：S3 插件初始化结构依据 [Flink S3 文件系统说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/filesystems/s3/)。本地 Flink 1.20.5 镜像已验证非 root 读取和复制实际插件 JAR；共享 PodTemplate、重复渲染、关闭清理和挂载冲突通过自动化测试，尚未在实际 Kubernetes 集群验收本次新增功能。


2026-10-04 编辑来源保护：新 JAR 表单写入 flink-web/editor=jar-v1；纳管作业标记 yaml，未标记旧作业默认 YAML 编辑。用户可在确认配置可能改变后尝试切换 JAR 表单，复制时也可选择编辑方式。解析失败保留 YAML；普通改标签不会解除限制。此标记用于防止误覆盖，不作为 Kubernetes 权限认证。

2026-10-04 故障说明：提供的部署快照显示 flink-main-container CrashLoopBackOff，同时旧清单曾把 S3 插件下载到 usrlib 并额外初始化到 plugins。本次消除双重插件加载路径和两个托管 initContainer；仅凭 CR 状态不能确定主容器退出根因，实际问题仍需结合 Pod 上一实例日志、退出码、Secret 与卷事件排查。插件目录要求依据 [Flink 1.20 S3 文档](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/filesystems/s3/)。


2026-10-04 Flink UI：现场 Pod 内直连 /overview 返回 Internal server error: Metaspace，已确认故障来自 JobManager 类元数据内存，而非平台代理。建议先把 jobmanager.memory.jvm-metaspace.size 调为 512m，JobManager 总内存调为 2048m，按状态保留策略重新部署；仅增加总内存不会自动提高 Metaspace 上限。2026-10-09 起 JAR 表单移除 JM/TM Metaspace 独立输入，已有值保留在 Flink 配置中；新建无模板作业默认 JM 总内存 2048m、Metaspace 512m，已有 YAML/模板显式值不覆盖。仍失败需排查类加载器和实际内存需求。代理保留原路径及错误状态；入口遇到明确 Metaspace 响应展示修复说明，其他上游 4xx/5xx 记录作业 ID、部署名、路径和状态码，连接错误记录异常类型，不记录凭据或查询参数。依据 [Flink 内存排障](https://nightlies.apache.org/flink/flink-docs-release-1.20/zh/docs/deployment/memory/mem_trouble/)。


2026-10-08 Savepoint 停止排查：先确认作业正常 RUNNING、Savepoint 目录及访问权限可用，且没有升级中的配置。平台提交 savepoint 挂起并禁用 last-state 回退；Operator 完成后，手动刷新推进删除。新版 Operator 使用 FlinkStateSnapshot 时，平台需具有既有 RBAC 中的 flinkstatesnapshots 查询权限，且快照需完成并标记 UPGRADE、路径匹配；仅出现 upgradeSavepointPath 不能证明是 Savepoint，因为终态 Checkpoint 观测也会写入该字段。参考 [Operator SnapshotObserver](https://github.com/apache/flink-kubernetes-operator/blob/release-1.13/flink-kubernetes-operator/src/main/java/org/apache/flink/kubernetes/operator/observer/SnapshotObserver.java) 与 [FlinkStateSnapshotUtils](https://github.com/apache/flink-kubernetes-operator/blob/release-1.13/flink-kubernetes-operator/src/main/java/org/apache/flink/kubernetes/operator/utils/FlinkStateSnapshotUtils.java)。出现“未确认保存点”时检查快照状态及 Operator 日志；排查前不要删除保存点目录。2026-10-08 已仅用现场 test2/Flink 1.20.5 验收保存点完成后删除资源、再次启动携带保存点恢复，以及失败时保留资源；其他版本与组合仍需验证。

2026-10-08 容器检查说明：SQLite JDBC 需要从临时目录加载本地库。使用只读根文件系统时需提供可写且允许加载本地库的临时目录；本地 Docker 检查使用 /tmp 的 tmpfs，并显式设置 exec。noexec 挂载会导致本地库加载失败，此限制与 Savepoint 修复无关。


2026-10-09 前端启动与后端 DNS：Web 镜像从容器 /etc/resolv.conf 生成 Nginx 运行时 resolver，后端 Service 暂时缺失不会让 Nginx 退出；对应 API/Flink UI 请求会暂时返回 502，DNS 记录恢复后无需重启前端即可恢复。Kubernetes 中自动用 search 列表的 namespace.svc.* 补全默认 flink-web-backend，Compose 保持单段服务名。自定义跨 namespace 后端时设置 FLINK_WEB_PROXY_BACKEND_HOST 为完整 Service 域名；FLINK_WEB_PROXY_BACKEND_PORT 默认 8080，显式设置时必须为 1–65535 的数字。上述默认值用于独立服务部署；当前 application.yaml 的同 Pod StatefulSet 前端显式设置代理目标为 127.0.0.1:8081，不走后端 Service DNS。后端兼容 Service 的端口仍为 8080、targetPort 为 backend-http。Kubernetes 会为 flink-web-backend Service 自动注入 FLINK_WEB_BACKEND_PORT=tcp://服务IP:8080；前端不再读取该变量，避免启动时报 Invalid FLINK_WEB_BACKEND_PORT。依据 [Kubernetes Service 环境变量说明](https://kubernetes.io/docs/concepts/services-networking/service/#environment-variables)。无需关闭 Service 环境变量即可使用修复后的镜像。仅启动容错不能代替创建正确的 Service、selector、Endpoints 或修复集群 DNS。可用 `python scripts/check_web_proxy.py --image 镜像名` 验证 Service 自动注入变量下启动、独立参数覆盖及非法端口、无后端启动、补全 Service 名、后端上线及变更 IP 后恢复，包含路径、参数、请求体、Session/CSRF 请求头和 Flink UI 代理路径。容器仍以 UID 10001 和只读根文件系统运行，运行时文件写入已有可写 /tmp。

### 文件与密钥行为更新（2026-10-10）

SQL Application 和验证专用 Session 的 Runner 改为手动选择：在 SQL Runner 文件选择区进入共享 sql-runner 目录，或确认 `https://host/path/runner.jar`、`http://host:port/path/runner.jar`、`s3://bucket/path/runner.jar`。不会按镜像版本自动选取；运行中 Session 仍需停止后修改并重新启动。普通 Session 可以不选择 Runner。

所有共用文件选择区均支持确认外部 HTTP/HTTPS/S3 地址，文件名自动取末层；地址不带凭据、查询参数或片段。HTTP(S) 主机须由管理员配置到 FLINK_WEB_ARTIFACT_HOSTS，读取不跟随重定向，静态识别和下载器均拒绝本机/链路地址。S3 外部桶使用平台现有 endpoint 与凭据，须授予相应单对象读取权限。制品桶浏览所需 ListBucket 范围现在覆盖桶根；作业自动复制/清理范围保持配置前缀下的 UUID 目录。

配置管理中的加密密钥在实际提交时作为容器 env.value 展开，不再生成 fw-* Kubernetes Secret；用户显式登记的已有 Secret 引用和运维配置的下载器凭据 Secret 保留。预览恢复密钥占位符，发布记录隐藏环境变量字面值，停止前配置使用现有主密钥加密保存。部署资源中的实际值按本次用户要求可在 namespace 中读取，namespace 读取权限由运维管理。本次未自动删除先前生成的 Secret，未更新实际 Kubernetes 工作负载。

2026-10-10 镜像发布完成：完整 WARN 异常链、制品目录删除及第三方 S3 checksum/签名区域兼容改动已构建、验证并覆盖推送 app/web/downloader 的 0.0.4 标签，不更新 latest。摘要见上表，已核对仓库 manifest 正文 SHA-256、Docker-Content-Digest 和本地 config 镜像 ID。后端使用通过校验的离线产物及原 Dockerfile 相同 Kylin 运行层；前端按原 Dockerfile 构建；下载器复用已验证的原 Kylin/Python 3.12.15/boto3 1.42.70 运行镜像，仅叠加新的下载脚本，依赖版本不变。三份镜像均为 linux/amd64，app/web UID 10001、下载器 UID 9999。

验证结果：后端 163 项全部通过，含实际精确版本 SQL Parser、隔离 MinIO 上传/复制/整目录删除、批量删除 XML/Content-MD5 匹配、目录边界与幂等、完整异常栈/原因/suppressed 及凭据遮蔽。前端类型检查、生产构建、全源格式和 14 项受影响浏览器回归通过。实际 app/web 镜像通过只读根文件系统启动、临时 SQLite 健康检查、加密登录、完整后端异常栈输出和 14 项页面回归；代理/DNS 恢复检查通过。下载器实际镜像通过 8 项测试及 cn-north-4 区域 S3v4 真实下载。Java 分层、172 个类及 842 个方法/构造器的中文注释和补丁检查通过。

临时容器、网络、测试数据库和打包副本已清理，构建缓存已回收，保留已标记镜像及校验记录。未更新实际 Kubernetes 工作负载，未在华为 OBS 或现场 Kubernetes/Flink 作业中验证本次部署效果；已有 Pod 按授权流程重新拉取镜像后生效。

### 项目治理验证（2026-10-12）

运行源码、Dockerfile、依赖与实际工作负载配置未改动。本轮仅治理受跟踪 Parser 构建输出、历史生成日志和项目知识/维护检查入口，因此没有受影响的应用镜像内容，不重新构建或覆盖发布。已只读重新核对 app/web/downloader 的现有 0.0.4 manifest 正文 SHA-256、Docker-Content-Digest 与本地 config ID，均匹配上表；没有更新 latest 或实际 Kubernetes 工作负载。

后端 167 项（含真实 Parser 和隔离 MinIO）、Runner 7 项、下载器 8 项全部通过；Parser 重建、前端类型/生产构建/格式、Java 分层/中文注释和当前 Web 镜像的 DNS/代理恢复回归通过。Windows JUnit 子进程须显式设置独占可写临时目录，首轮默认系统临时目录权限问题已通过重跑解决。记录与维护入口见 [治理验收](agent/validation.md)；真实 MySQL、生产 Kubernetes/Flink 和其他 S3 厂商本轮未验收。

治理收尾例外：首轮JUnit在系统TEMP留下的两个测试目录受Windows删除权限限制，具体路径见 [待确认事项](agent/open-questions.md)；项目内临时验证目录与Docker资源均已清理，未修改系统ACL。

### 状态地址与已保存运行账号（2026-10-12）

状态根目录现统一读取 `platform.operator.state-root`（绑定 `FLINK_WEB_STATE_ROOT`），修复 S3 服务读取旧 workload 属性而回退默认值的问题。例如 `s3://flink-web/flink-state` 应生成 `s3://flink-web/flink-state/{UUID}/checkpoints`、`/savepoints`、`/ha`，不会丢弃桶后前缀。修改根目录不迁移历史对象，不自动修改已运行作业或手工 YAML 目录。

修改 ServiceAccount 后，“保存并部署”按最新草稿提交；启动/恢复时，无论旧资源仍在还是停止后重建，有编辑权限的操作者均采用已保存草稿的显式账号，其余配置继续复用停止前快照。仅运维权限的启动仍复用原账号。仅在表单预览修改但尚未保存不会影响集群；已有运行资源只有显式部署才更新。账号及 RBAC 需在 namespace 预先存在。

2026-10-12 状态根目录、S3凭据与旧作业账号修复发布：app/web 的 0.0.4 已构建、实际镜像验证并覆盖推送，最终摘要见上表；downloader 保留原摘要，未更新 latest。复用现有已验证的 Kylin/JRE 和 Nginx 运行层，分别替换离线测试通过的后端JAR和前端生产产物，依赖版本不变；均为 linux/amd64、UID 10001。使用同一registry的 localhost:15000 映射推送，随后从指定 192.168.68.1:5000 地址校验 manifest正文SHA-256、Docker-Content-Digest、本地config ID一致。

最终校验：后端176项全部通过（含真实精确Parser、隔离MinIO、完整状态根绑定、Flink配置内密钥、Secret引用不可读/非法值拒绝与请求级读取、历史脱敏，以及旧资源直接启动/删除后重建的管理员、开发者和运维边界）。前端类型/生产构建/全源格式、Java分层及172类/858方法构造器中文注释检查通过。实际新app/web在非root、只读根文件系统、临时SQLite下通过真实加密登录、状态默认值、配置内AK/SK、无主容器AWS AK/SK、密钥安全预览、旧作业重新部署、停止后新账号启动及旧资源仍在的直接启动；Kubernetes写入使用隔离协议替身，未连接现场集群。实际Web上6项账号/模板页面回归、代理/DNS恢复检查通过。

### SQL 启动、JDK 兼容与工作台发布（2026-10-12）

已构建、验证并覆盖推送 app/web:0.0.4，不更新latest，downloader保持原版本；第4节摘要已通过指定仓库manifest正文、header和config ID核对。使用全量校验通过的本地后端、Java8字节码Runner、两份精确Parser与前端产物，复用已验证的Kylin SP3运行层；app/web均为linux/amd64、UID/GID10001。标准backend/Dockerfile同步构建和安装两份Parser及下载用Runner。

验收：后端178项、Runner8项、实际Web15项交互回归；JDK8/17真实Runner与双版本Parser；Flink1.20.5/JDK17初始SQL Application及Session验证100条/动态依赖均通过。实际非root只读镜像通过加密登录、1.20.5 PARSER检查和Runner下载哈希匹配；Web DNS/代理、前端类型/构建/全源格式、Java分层及172类/863方法构造器中文注释检查通过。详情见[知识验收记录](agent/validation.md#sql-运行与-jdk-兼容验收2026-10-12)。

未更新实际Kubernetes工作负载或已有SQL/Session资源；现有平台Pod须重新拉取镜像，旧SQL草稿须按第6节重新选择新版Runner并发布，验证Session须停止后更新Runner再启动。现场地址首页可达，浏览器工具启动失败，未读取登录后现场作业配置；本轮成功结果来自隔离真实Flink和本地协议/浏览器验证。

本轮独占验证容器、网络、数据库、日志、临时凭据/打包副本及浏览器产物已清理；构建缓存回收513.2MB，保留已标记镜像、交付JAR与共享Maven依赖。

旧草稿/运行资源的手工状态目录不会自动迁移；新建或显式重新启用状态存储使用完整新根目录。已有作业要采用新的S3配置表达方式，应检查草稿后显式重新部署。已保存ServiceAccount在具备编辑权限的启动/恢复操作中生效，仅运维权限继续用原账号。

未操作实际Kubernetes工作负载，未验证现场账号/RBAC、真实Flink调度或恢复兼容性。复用0.0.4标签时现有Pod须重新拉取；仅推送镜像不会改变运行中的实例。本轮隔离容器、网络、测试凭据、临时库与打包副本收尾清理，构建缓存清理后保留已标记镜像。


2026-10-13 归档补核：10-08/10-09 聊天中的独立前端 Deployment 更新命令及桌面 YAML 检查属于当时环境；桌面附件原件现已缺失。本仓库主清单和 frontend-runtime-patch.yaml 已针对合并 StatefulSet/flink-web，前后端 Always、Pod enableServiceLinks=false。另一本SQL/Session聊天10-09生产图已有flink-web-0双容器2/2运行，同时请求Kubernetes失败；该图支持当时合并Pod已运行，但当前控制器与最新修复镜像摘要未核查。执行更新前先按授权核对资源类型，不将旧rollout命令盲套新清单。旧chat中的0.0.5/latest及共享S3插件下载不属于当前发布/插件规则；最新摘要继续以上表和后续发布记录为入口，本文补录不重新发布镜像或重新认证摘要。历史正文/附件/测试范围分别见 [作业与前端聊天核对](agent/validation.md#作业与前端修复聊天归档核对2026-10-13) 和 [SQL/Session聊天核对](agent/validation.md#sql-与-session-开发聊天归档核对2026-10-13)。

### 2026-10-15 Session 界面与模型需求向导发布

app/web 的0.0.4已覆盖发布至192.168.68.1:5000/flink-web，摘要以上表为准；未更新latest/downloader或实际Kubernetes工作负载。复用原Kylin/JRE17/Nginx层，仅替换通过校验的JAR/前端产物，linux/amd64、UID10001、只读根文件系统验证通过。由于守护进程代理不可用，使用同一registry的localhost:15000映射推送，再从指定192.168.68.1:5000校验manifest正文/响应摘要、config及全部21/20层的压缩内容和解压diff_id一致。

后端继续使用Spring Boot3.5/Tomcat；BES11.5.0.x按用户最新要求暂缓。ModelScope根地址可自动补v1，推荐预设前缀https://api-inference.modelscope.cn/v1、模型Qwen/Qwen3.5-27B，密钥由管理员加密保存，不能写入清单或镜像。实际认证测试返回403：账号需完成邮箱验证；未认证真实生成效果。

Session代理按嵌套features关闭Web提交/取消/缩放，并拦截JAR路径，已有运行集群在平台升级后也隐藏Submit New Job。新建/显式重启的普通Session强制web.submit.enable=false；专用SQL验证Session保留true以便内部JAR REST，不允许用户从平台代理上传。真实Flink1.20.5已验证这一差异，不能把该开关设为false后仍声称后台提交可用。运行配置变更须在用户授权下显式停止/启动，平台不自动重启现场资源。

其余界面、SQL提示、高亮及验证范围见 [本轮验收](agent/validation.md#session-界面与模型需求向导2026-10-15)。Flink2.3资料仅作编辑提示依据，不增加运行版本认证。

### 2026-10-15 需求向导约束与验证最终结果发布

app/web:0.0.4 已覆盖发布，上表为本轮最新摘要；latest/downloader 和实际 Kubernetes 工作负载未改。沿用已验证 Kylin/JRE/Nginx 运行层，替换后端、新 Runner 和前端产物，无依赖升级/数据库迁移。app config `sha256:154d66b0fc8c90356ab12f512ec186cd9a18687db1701718310b7eea8cc5690c`，web config `sha256:475e4588da1957619f732739a3155dcb96d0cb78ac052c972479922dfefca99a`；经同一 registry 的 localhost:15000 推送后，从指定192.168.68.1:5000核对 manifest 正文/响应摘要、config及全部23/21层的大小、压缩SHA-256和解压diff_id一致。

使用前需重新拉取平台镜像，并通过“下载 SQL Runner”取得新包（SHA-256 `42e17ae9807c5e048c8df112f735fbb8677b8520ceda3f1c72ce7b8eba3164a5`），上传后在既有 SQL 草稿及验证 Session 中显式重新选择；验证 Session 的运行中配置须按授权停止、修改、重新启动。平台不会覆盖已选旧文件。新 Runner 跳过原 Sink 的连接选项，按字段/主键生成 Print JSON 输出；支持源表加 SELECT，Application 仍须 INSERT。旧 Runner 缺少结构化字段时页面明确提示升级，不猜测旧文本；原固定日志配置不变。

完整后端、Runner、相关页面、JDK8/17、实际 Flink1.20.5及最终只读非root镜像闭环范围见 [本轮验收](agent/validation.md#需求向导约束与验证最终结果2026-10-15)。真实模型厂商与生产 Kubernetes 未新增认证；临时验证资源及已核实的本轮构建缓存按ID清理，保留发布镜像、已有registry和共享资源。

## AI 与 SQL 五项修复发布（2026-10-16）

app/web:0.0.4已覆盖发布，上表为最新manifest；通过同一registry的localhost:15000映射推送。复用原Kylin/JRE/Nginx运行层，替换已通过校验的后端JAR和前端产物；app config为 `sha256:2fae755bc534e9fd7a794214ebf7c68515a9d614c0ffd7def345805d9ba92319`，web config为 `sha256:51dfb11c883ab22b16c12f56a5dcb858797d65a80108679d03ff768c63340a18`。从指定192.168.68.1:5000核对manifest正文/响应摘要、config以及全部24/23层大小、压缩SHA-256和解压diff_id一致。本轮不改Runner/Parser、latest、downloader或实际Kubernetes工作负载；已有Pod需按授权流程重新拉取平台镜像，旧Runner升级规则继续适用。

需求变化后第四步重新禁用；长SQL显示截断与完整审阅入口；超100列提示减少字段，旧协议提示重新选择新版Runner，损坏结构提示检查日志；SELECT/CTE/VALUES可验证，CTAS在基础检查提前拒绝。后端235项、最终Web25项及实际镜像/代理校验范围见 [本轮验收](agent/validation.md#ai-与-sql-五项修复2026-10-16)，不代表真实厂商或生产集群新增认证。

## AI 准备取消与结果标识发布（2026-10-19）

app/web:0.0.4已覆盖推送，最新manifest见上表；保留Kylin/JRE/Nginx原运行层，仅替换本次通过校验的后端JAR和前端产物。app config `sha256:03c1fcfd0eb000367cac36710a17b2d6b20f5760e5f7749d65b25f90351fc34d`，web config `sha256:22e2041b6f3b1013f25eac04e932927e04ae80ed42544aaa99e987377b90319c`，均linux/amd64、UID/GID10001。沿用原registry的localhost:15000映射推送，从指定192.168.68.1:5000核对manifest正文/响应摘要、config及各25层大小、压缩SHA-256和解压diff_id均一致；不改latest/downloader、Runner/Parser分发文件或现场Kubernetes工作负载，既有Pod需按授权流程重新拉取平台镜像。

本轮发现Docker切到C盘默认目录和containerd模式，用户明确要求恢复并提供原目录。原磁盘为 `D:\DevTools\docker\DockerDesktopWSL\disk\docker_data.vhdx`；settings-store.json的CustomWslDistroDir引用其父目录 `D:\DevTools\docker\DockerDesktopWSL`，UseContainerdSnapshotter=false恢复原overlay2存储。先正常停止再修改设置/重启，旧镜像和容器数据已恢复，未移动/覆盖磁盘。沿用已有registry容器及 `D:\DevTools\registry` 绑定目录、127.0.0.1:15000映射；原192.168.68.1:5000到15000的端口转发保留，指定仓库/v2/恢复200。Docker发布环境恢复不等于更新任何Flink/Kubernetes工作负载。

后端237项中236通过、可选MinIO跳过；最终Web26项及真实镜像登录/正常AI单次调用/Parser、代理回归通过。准备阶段取消零调用、1000条事件结果一致和计算量测量的详细范围见 [本轮验收](agent/validation.md#ai-准备阶段取消与结果标识优化2026-10-19)。

## BES 运行时与镜像瘦身（2026-10-19）

用户提供 BES starter 要求替换 Tomcat，随后要求 app、web、downloader 统一发布 0.0.7 并全部瘦身；替代此前暂缓 BES 和沿用 0.0.4 的约定。保留 Boot3.5.16、Java17、Kylin V10 SP3 和非 root/只读根运行条件。附件原样保存，内部 Maven 坐标、file 仓库、校验及 mirrorOf 配置见 [vendor依赖](../backend/vendor-repository/README.md)；不依赖开发者桌面路径，不提取授权内容到知识库。

旧 app/web 在上一版业务镜像上反复覆盖 JAR 或 dist，删除文件也不能回收历史层。现在三个正式 Dockerfile 都从干净 Kylin 构建运行层：

- [app](../backend/Dockerfile)：移除重复 JAR/Parser 和额外软件包安装，采用 [Spring Boot 3.5分层提取](https://docs.spring.io/spring-boot/3.5/reference/packaging/container-images/dockerfiles.html)；依赖与应用分层复用。
- [web](../frontend/Dockerfile)：构建阶段安装 Nginx，再由 [导出脚本](../frontend/prepare-nginx.sh) 仅带入实际使用的程序、MIME、额外共享库及安装包提供的声明；最终镜像不装入整套软件包依赖，也不保留旧 dist。自定义配置不加载额外动态模块。
- [downloader](../downloader/Dockerfile)：[精简脚本](../downloader/prepare-runtime.py) 移除 CPython 自带测试、静态库/头文件、构建配置、pip/ensurepip、缓存和开发工具，随后去除解释器及扩展的调试符号；保留标准库、boto3/botocore 服务数据、元数据和运行组件许可。默认底座已有 SSL/压缩库、CA 和账号工具，仅额外复制同麒麟构建的 libffi。

禁止再从上一版业务 app/web/downloader 镜像继承后覆盖文件。默认源码构建命令见第4节；替换 KYLIN_BASE 时须核实原生库、CA 和账号工具兼容性。本轮因外部镜像/软件源连接受限，实际使用已验证的同底座组件和宿主构建产物，经 [Docker命名context](https://docs.docker.com/build/concepts/context/#named-contexts) 覆盖产物阶段，最终运行层仍共用正式 Dockerfile；未声称联网源码 Docker 构建已完成。

离线构建前必须先验证目录中的产物和版本。APP_VERIFIED 包含 app.jar、runner.jar、validators/1.20.3/validator.jar、validators/1.20.5/validator.jar；NGINX_VERIFIED 是导出脚本生成的根目录；PYTHON_VERIFIED 包含精简并 strip 后的 opt/python、usr/lib64/libffi.so.7 及许可目录；WEB_DIST 是本轮类型检查和生产构建通过的 dist。

~~~powershell
docker buildx build -f backend/Dockerfile --build-context artifacts=APP_VERIFIED -t 192.168.68.1:5000/flink-web/app:0.0.7 --load .
docker buildx build -f frontend/Dockerfile --build-context nginx-artifacts=NGINX_VERIFIED --build-context web-artifacts=WEB_DIST -t 192.168.68.1:5000/flink-web/web:0.0.7 --load frontend
docker buildx build -f downloader/Dockerfile --build-context python-artifacts=PYTHON_VERIFIED -t 192.168.68.1:5000/flink-web/downloader:0.0.7 --load downloader
~~~

BES starter 未映射 basedir 且优先读取进程 installRoot，[适配配置](../backend/src/main/java/com/flinkweb/config/server/BesServerConfiguration.java) 显式同步。其文档根仍相对 user.dir/appserver 创建，因此仅将 /app/appserver 链接至 /tmp，保留 /app 工作目录及相对路径语义；数据库保持 /app/data/flink-web.db，Parser/Runner 保持绝对路径。/tmp 须可写，SQLite JNI 使用允许 exec 的临时卷。未迁移原数据库或修改实际 Kubernetes 工作负载。

三个 0.0.7 已推送，经原 registry 的 localhost:15000 映射发布，再从指定 192.168.68.1:5000 核对 manifest 正文/响应摘要、config、全部压缩层大小/SHA-256 及解压 diff_id 与已测试镜像一致。app/web 为 UID/GID10001，downloader 为 UID9999，均 linux/amd64；不更新 latest。用户切换版本前 app 曾按旧约定发布同一 BES 内容到 0.0.4，最终交付统一采用 0.0.7。

下表滚动维护当前镜像；本节其他0.0.7内容为历史，旧版已于2026-10-21清理。

| 当前镜像 | 仓库 manifest SHA-256 |
| --- | --- |
| 192.168.68.1:5000/flink-web/app:0.0.8 | sha256:ea8d57990cece64b41b3d4e4a5677e22546a73ad91e6921b3871fddf3bcde3b0 |
| 192.168.68.1:5000/flink-web/web:0.0.8 | sha256:10e9b8c6518b192f523a0cabbf45616debda76c352a4799034a4ab39430ad537 |
| 192.168.68.1:5000/flink-web/downloader:0.0.8 | sha256:ee210b7e3e0e3d3afb42febe34dd78da52ee997c2819a845ff761dc487ae8dc0 |

| 镜像 | 原逻辑字节 → 新逻辑字节 | 原压缩层字节 → 新压缩层字节 | 原层数 → 新层数 |
| --- | --- | --- | --- |
| app | 1,679,404,280 → 585,001,401（-65.2%） | 1,269,253,098 → 282,274,156 | 25 → 10 |
| web | 446,050,884 → 310,963,280（-30.3%） | 157,074,444 → 111,325,456 | 25 → 8 |
| downloader | 760,604,373 → 392,503,474（-48.4%） | 244,289,216 → 146,378,698 | 6 → 5 |

体积采用同一 Docker/registry 口径，逻辑大小包括共享 Kylin 层，不代表 Docker 磁盘文件占用。后端238项中237通过、1可选独立MinIO目录用例跳过；最终三镜像另通过真实隔离MinIO和只读运行验证，详见 [验收](agent/validation.md#bes-运行时与镜像瘦身2026-10-19)。Web代理恢复、下载器8项及真实S3/中文文件名/插件/启动脚本/超限清理均通过。

[部署清单](../deploy/kubernetes/application.yaml) 三处引用已改为0.0.7，本次未应用到集群。已有系统设置保存的全局下载器绑定优先于环境变量，升级时须同时选择0.0.7下载器；本次不修改已有数据库中的绑定或已运行作业。生产BES授权、MySQL及现场Kubernetes不在本轮新增认证范围。

## 仓库旧版本清理（2026-10-19）

用户明确要求清理 `192.168.68.1:5000/flink-web` 的其他版本。本次 app、web、downloader 仅保留0.0.7；三个0.0.4标签及63份旧历史manifest已删除，旧日期的发布摘要只保留作为历史记录，不能再按旧标签或已清理摘要从此仓库拉取。当前0.0.7摘要与上表一致。

app的0.0.4与0.0.7曾共享同一manifest，因此先使用Registry3.1.2的 [按标签删除分支](https://github.com/distribution/distribution/blob/v3.1.2/registry/handlers/manifests.go) 只去除旧标签，保留共享内容。再删除指定三个仓库内其余历史manifest。不能直接按共享摘要删除旧版本，否则会影响保留标签。

按 [Distribution垃圾回收要求](https://distribution.github.io/distribution/about/garbage-collection/) 停止原registry后运行原生dry-run；独立计算所有仓库保留manifest的递归引用集，确认382个候选全部属于旧flink-web且不与保留内容共享，才执行原生GC，随后恢复原容器与端口映射。未使用全仓库delete-untagged，也未修改其他仓库的标签或manifest。

仓库blob数据逻辑大小6,366,328,217→925,849,866字节，回收5,440,478,351字节（约5.44GB/5.07GiB）；不等于Docker虚拟磁盘缩容。三个0.0.7的manifest/config/layer及其他仓库保留内容共46个digest均经HTTP读取并校验SHA-256，旧标签返回404，仓库/v2/恢复200。`flink:1.20.5-scala_2.12`、`registry:3`保持原摘要。没有清理本机Docker镜像、数据库或Kubernetes工作负载；实际清理范围和验收见 [维护记录](agent/validation.md#仓库旧版本清理2026-10-19)。

## AI 三步对话与 SQL 默认值发布（2026-10-19）

app/web继续覆盖0.0.7，上方“当前镜像”摘要已更新；downloader保持原0.0.7。本次从干净Kylin基础按正式Dockerfile及已验证产物重建，不继承业务镜像；app保留BES/Java17/Boot分层。app逻辑585,012,186字节、压缩层282,284,085字节、10层；web逻辑310,956,016字节、压缩层111,322,817字节、8层，保持上一轮瘦身范围。

AI对话和总结复用元数据集合，无新表迁移；更新后保留数据库及原加密主密钥。新建SQL为空并先确认不可变名称，默认升级策略stateless，已有显式策略不覆盖。未显式选择Runner时，SQL及验证专用Session使用默认对象键`artifacts/shared/sql-runner/flink-web-sql-runner.jar`（跟随配置rootPrefix）。管理员须将匹配的Runner上传到该共享目录；内置下载入口继续提供文件，平台不自动替换共享对象、显式选择或运行中Session。文件不存在/S3未配置/读取失败仍报错，不退回另一份Runner。

后端243项均实际执行通过（包含补跑的两版Parser及真实隔离MinIO），最终Web42项相关回归和真实BES加密登录/持久化会话/确认生成/报错修复/保存重进/默认Runner/YAML/发布跳转通过；Web代理恢复通过。指定仓库manifest正文/响应摘要、config及全部10/8层压缩SHA-256、大小和解压diff_id与已测试镜像一致。[具体验收范围](agent/validation.md#ai-三步对话与-sql-默认值2026-10-19)。未更新现场Kubernetes、原数据库或共享Runner文件；模型使用隔离协议替身，不宣称真实厂商或生产Planner认证。
## AI 布局与 SQL 发布修复（2026-10-20）

app/web覆盖0.0.7，最新manifest见“当前镜像”表；app config `sha256:ed16e4e2f40244c75e0e6426e1a651a3e658c91b406ac0848666a5b71e57c4a6`，web config `sha256:c289890b0b06483139e679c863a5f8dd147dc09cf47f8da045e15bad3936c9ca`。本轮沿用正式Dockerfile和已验证命名context，从干净Kylin基础重建，不继承业务镜像；app逻辑585,016,127字节/10层，web逻辑310,973,245字节/8层。通过原registry的localhost:15000映射推送，从192.168.68.1:5000核对manifest正文/响应摘要、config以及全部压缩层大小/SHA-256和解压diff_id，与已测试镜像一致。downloader保持原0.0.7，不更新latest，未应用到实际Kubernetes。

SQL编辑后仍须保存并生成部署草稿（或部署并启动）。启动遇到保存但未发布的新SQL会明确拒绝，最新已发布SQL不再被旧停止快照覆盖；仅有运维权限不能借启动替换SQL。“核对部署SQL”直接解码Runner的`--statement` Base64参数，比较当前待发布/已发布/集群声明；源注解为`flink-web/sql-sha256`及`flink-web/sql-revision`，旧部署无注解时明确显示旧版本。集群声明一致仅证明参数已提交，实际语法/结果仍通过SQL检查/专用Session验证及运行观测确认。

后端246项、最终Web49项、真实非root只读BES/AI持久化和显式确认/SQL发布核对/代理恢复均通过，详见[验收](agent/validation.md#ai-布局与-sql-发布修复2026-10-20)。无数据库迁移，保留原主密钥；未替换共享Runner、未连接现场Kubernetes，模型为隔离协议替身。更新运行环境需按授权流程重新拉取0.0.7，发布不自动重启现有Pod。

## 开发列表与版本清理统一（2026-10-20）

app/web覆盖发布0.0.7，当前manifest表已更新。app config `sha256:861ee62251f3bb9e72bff512b7b2745c45c9176b349bd792a1bd385e7267ec61`，web config `sha256:012f40744d7b39d79f7d7cc74493889775054bc1c3c588432f0b5e5a2b4b7d17`；两者linux/amd64、UID/GID10001，逻辑字节585,017,066/310,978,186，压缩层282,288,852/111,331,671，10/8层。继续使用正式Dockerfile/验证命名context从干净Kylin构建，通过原registry的localhost:15000映射推送，从192.168.68.1:5000逐层核对摘要、大小及解压内容一致。downloader保持原0.0.7，不更新latest。

本轮统一三处列表状态和JAR/SQL开发布局，新增JAR命名确认与表单/YAML拖动。SQL保存版本、作业发布版本按主体各留5次，旧超限历史在读写时修剪，不删除审计或保存点文件；没有数据库迁移。删除作业清理当前jobs/id及旧prefix/id的专属程序目录和标记，保留共享与邻居；仍须先确认集群资源消失，SQL草稿依赖按原迁移规则保留。

后端248项、最终Web84项、真实非root只读镜像登录/历史保留/命名/删除及代理检查通过，[验收范围](agent/validation.md#开发列表与版本清理统一2026-10-20)不代表现场Kubernetes或MySQL/提供商认证。原数据库/主密钥/共享Runner保留，实际工作负载未更新；需按授权流程重新拉取0.0.7才应用新功能与保留策略。

## 作业 AI 持续诊断与确认修复（2026-10-20）

用户选择方案一侧栏、底层按独立取证/会话/修复服务设计，并授权同步重启现场平台，要求一次只验证一个作业；随后明确PVC不用备份。本次没有备份或替换PVC、数据库和原加密主密钥，没有数据库迁移。旧版本“未更新现场”说明属于各自历史范围。

app/web覆盖0.0.7，当前manifest见上表；config分别为`sha256:a5430ce72c9bf9aee2054a5c5dfb9bbf84af0831c038d228e2535065f89bc340`、`sha256:26c7ab516f17c044e996e7e5793d10f5bffdfd2a851b0d75b6a6daa19baa788b`，逻辑585,052,810/310,996,426字节、压缩282,323,445/111,336,934字节、10/8层。正式Dockerfile、已验证命名context、干净Kylin底座及非root/只读条件不变；先通过真实隔离镜像登录、两轮分析/修复、确认仅保存、恢复会话、删除清理，再经原registry localhost:15000发布。指定192.168.68.1:5000的manifest正文/响应摘要、config、全部压缩层大小/SHA-256及解压diff_id均核对一致。downloader保持原0.0.7，不更新latest或共享Runner。

现场`flink-kubernetes-operator` namespace的单副本`StatefulSet/flink-web`保留原配置，沿用Always拉取策略执行滚动重启；`flink-web-0`两个容器Ready且imageID与上述manifest完全一致，站点登录和新助手可用。只创建一个临时SQL作业：JobManager CPU100导致真实FailedScheduling，经既有Qwen/Qwen3.5-27B分析/持续对话生成CPU0.25及SQL n+1建议，完整对比后显式选择stateless，保存/发布/dry-run/deploy受理，随后实际RUNNING/STABLE且Print输出从2开始。JM/TM各1024m、各0.25CPU、并行度1；没有启动原有五个作业。完整后续日志、清理及提交记录见[验收](agent/validation.md#作业-ai-持续诊断与确认修复2026-10-20)。

## AI 新轮次与分类取证待发布（2026-10-20）

工作分支`codex/ai-fresh-round-evidence`尚未提交或合并；后端及Web静态产物已本地构建，验证与产物摘要见[本轮验收](agent/validation.md#ai-新轮次与分类取证2026-10-20本地完成发布待恢复)。用户明确关闭K8s，本机Docker Engine也停止，实际未构建/验证/推送镜像、未更改运行工作负载，不更新此处已发布镜像摘要。无数据库迁移，PVC/数据库/主密钥保留且不备份。

环境恢复后沿用正式Dockerfile，从干净Kylin底座构建app/web:0.0.7（不更新latest/downloader），先做隔离实际镜像登录/CSRF/分类取证/修复恢复与代理检查，再推送原仓库并核验manifest/config/全部层。`deploy/kubernetes/rbac.yaml`已准备namespace Role对`metrics.k8s.io`组`pods`资源的`get`补充；仅用于按需Pod CPU/内存查询，不授权跨namespace、节点或写入。真实metrics-server不存在时仍显示不可用，不主动安装组件。

在已有平台更新授权下应用对应Role补充、重启单副本StatefulSet/flink-web并核对两容器Ready/imageID。现场只能串行验证一个作业，复核重启受理关窗/新空态、最新SQL和配置、故障方向分类采集、合法Secret引用、显式确认应用；临时资源清理后，再按AGENTS提交工作分支并合并本地master。实际Flink REST/JVM/GC/Checkpoint、Pod metrics与平台RBAC均以恢复后的现场结果为准。

## AI 抽屉调宽与继续对话（2026-10-20）

仅web:0.0.7覆盖发布，manifest `sha256:a99f4339a462c1e314552c2528e2db47fcfdfae8eb6e705ab6f5825c0f7d7777`，config `sha256:7f31576a245f3dc546ea21b9cfb3e5976b5da0a6b8799a24645128ff865f9dc2`；逻辑311,009,868字节、压缩111,340,373字节、8层。从正式Dockerfile和干净Kylin底座构建，已验证命名context提供Nginx运行件与前端dist；非root/只读镜像32项相关页面及启动/DNS代理4项通过，经原localhost:15000映射推送后，从192.168.68.1:5000核验manifest正文/响应摘要、config、全部压缩层及解压diff_id一致。

在既有授权下重启StatefulSet/flink-web；frontend实际imageID为上述manifest、backend仍为上一节app `sha256:8553bb3812c75be9da5564e1c287cea16d5250ef0edcbdb702f2b268d01bd8e0`，两个容器Ready。app/downloader/共享Runner/latest不改，无迁移，PVC/数据库/主密钥保留且不备份。此次不启动、停止或修改用户作业；现有test仍FAILED，旧会话/总结保留，修复的是继续授权发送入口及页面布局。[验收范围](agent/validation.md#ai-抽屉调宽与继续对话2026-10-20)。

## AI 阅读布局与统一总结（2026-10-20）

本轮覆盖app/web 0.0.7，最新manifest见上表；app config为sha256:cf33d0956e7e149aa8511b887e50a96eabf95cb0849605a2c90542d9dc0f2d7a，web config为sha256:901606a1af348ce803ef54d1f875ba2782f19a6e832f8d392e19e62cb305e076。逻辑585,061,177/311,006,413字节，压缩282,331,663/111,339,775字节，10/8层。沿用正式Dockerfile及已验证命名context，从干净Kylin构建；只读非root镜像先通过登录、唯一总结保存/格式化大视图、宽度及删除，再经原localhost:15000映射发布，从192.168.68.1:5000核验manifest正文/响应摘要、config、全部压缩层大小/哈希和解压diff_id一致。downloader保持原0.0.7，latest和共享Runner未改。

在用户既有授权下滚动重启单副本StatefulSet/flink-web，原PVC/数据库/主密钥保留且不做备份，无DDL迁移。规范总结与运行标记复用platform_records及主体索引，按需兼容旧总结；不迁移其他存储。实际仅运行一个临时SQL作业，真实模型完成共享总结分析/修复并经显式stateless应用达到RUNNING/STABLE；受理不清消息，成功后清旧排障、保留唯一总结，SQL入口展示同一总结。临时作业/SQL及4份AI正文/3条索引最终清零，原五作业版本不变。[完整验收](agent/validation.md#ai-阅读布局与统一总结2026-10-20)。

## AI 一致性修复与版本发布（2026-10-21）

本次按用户要求将app/web/downloader统一升级0.0.8并清旧版，包含10-20未发布的新轮次/分类取证与本轮六项优化。三组件沿用正式Dockerfile、干净Kylin底座和已验证命名context，未继承旧业务镜像；BES/Java17、非root/只读根、SQLite原路径、Parser/Runner保持。后端离线产物缺少任一精确Parser文件即失败；Python精简先验证专用标识及路径/全部链接，再删除构建文件。

| 组件 | config SHA-256 | 逻辑字节 | 压缩层字节 | 层数 |
| --- | --- | ---: | ---: | ---: |
| app:0.0.8 | sha256:d5b6acbc893734f486c83681d9d8afcec1d395a5cdcad8a5e37cfe9826e9976a | 585,079,784 | 282,349,643 | 11 |
| web:0.0.8 | sha256:40f487cccb2f1d4f9f8ed98f331dd8e7ba78deabd47c8081016cb42ec53a3772 | 311,020,285 | 111,343,592 | 8 |
| downloader:0.0.8 | sha256:f35eed19bbc787181949acf926499be696614adabbb9c2b3e985a0362b8fbb2c | 392,503,512 | 146,379,002 | 5 |

manifest见上方当前表。经原registry localhost:15000映射推送，从指定192.168.68.1:5000核对manifest正文/响应摘要、config、全部压缩层大小/SHA-256及解压diff_id。后端286项中285过/1可选MinIO跳过，最终Web35项、下载器Linux12项、双Parser/加密登录/AI持久化与清轮、代理恢复通过；实际K8s/Flink/模型为隔离协议替身，详细边界见[验收](agent/validation.md#ai-六项优化与版本发布2026-10-21)。

清理三个0.0.7标签、19份旧manifest，原生GC在归属/保留引用核对后回收101个旧blob，共525,215,072字节；恢复原registry后47个保留digest全部复核，磁盘blob集合精确一致。其他flink/registry标签与摘要不变。本地旧组件标签/推送别名、4个新增私有缓存已清；保留正式0.0.8镜像、共享缓存/基础镜像。历史发布摘要仅作记录，不能再按旧标签/已清摘要拉取。

实际K8s仍关闭，本次未重启平台或作业。恢复时应用更新的application.yaml（app/web及下载器环境值均0.0.8），并检查系统设置中已保存的全局下载器绑定：持久化绑定优先于环境值，若仍是0.0.7必须显式改为0.0.8后才启动作业。已有Pod中的旧initContainer配置也应随明确部署更新，不能只重启一个仍引用旧下载器的作业。metrics.k8s.io/pods只读RBAC补充已准备，未现场应用或安装metrics-server。单后端实例、原PVC/数据库/主密钥/共享Runner保留，不做PVC备份，现场继续一次只验证一个作业。


## 普通作业日志配置（2026-10-23）

JAR/SQL/普通Session提供折叠日志编辑，新表单含INFO/控制台/滚动文件默认；已有配置不自动覆盖，保存后需明确部署/重启。普通表单自定义日志与专用SQL验证Session固定协议分离。Print/System.out默认不是文件日志，需要转入时显式配置taskmanager.system-out.mode=LOG。

app/web继续0.0.8覆盖发布，manifest见当前表；config分别为sha256:f41fc450b233658f0693f6424fd48b1151beebc838c05261f86706edcdded34d、sha256:c622f3c7ed124f9da19e6899b212a54340d6a841fd9b404bffe860df22148653。正式Dockerfile/干净Kylin底座/验证命名context构建，非root只读镜像验证后推送，经指定仓库核验manifest/config/全部11与8层大小、摘要及解压内容。downloader摘要不变。

现场站点本轮不可达，未更新实际K8s；已有作业需要自行选择默认或自定义日志、保存并部署/重启才生效。原PVC/数据库/主密钥/Runner不变、不备份PVC。[测试范围及既有失败说明](agent/validation.md#普通作业日志配置2026-10-23)。

### AI与SQL视口布局（2026-10-25）

仅web继续覆盖发布0.0.8，manifest见当前表，config `sha256:b4f8bbc806a91a03cb0eaea4e84b8f10fcf52893430fff6ae63b6c8d2ee52efe`。正式Dockerfile与干净Kylin构建，非root只读最终镜像38项相关页面及代理4类通过，指定仓库manifest/config/全部8层压缩与解压摘要核对一致。app/downloader/latest不变；现场30080端口不可达，未更新实际K8s或启动作业。更新已有工作负载后才会显示本轮布局；验证范围见[验收](agent/validation.md#ai-与-sql-视口布局2026-10-25)。

## Catalog、UDF、SQL与日志（2026-10-25）

采用正式 Dockerfile、干净 Kylin 运行基础及验证后的离线制品覆盖发布 app/web:0.0.8，不更新 latest；downloader 保持。指定仓库 `192.168.68.1:5000/flink-web` 已逐层核对 manifest 正文/响应摘要、镜像 config、全部压缩层大小/哈希和解压 diff_id。

| 组件 | Manifest | Config | 层数 |
| --- | --- | --- | --- |
| app:0.0.8 | `sha256:1e2add538775358d863e8d5cf6da741e76f0997d4d0cdd72b549476abb59dc6a` | `sha256:0ad828b649a22d4b5982968171333c5b3bd9c07d70694139dcc618953a460b89` | 11 |
| web:0.0.8 | `sha256:85257fdae54377a4133cad0dfcc47176d79ffb19eae3efebaf0bd65510386145` | `sha256:efe093c8f63fcb4d8439b7e0ae4981ad471b273cdd9c827d2642c9e2b2931067` | 8 |

现场 StatefulSet/flink-web 已重启，两容器 Ready 且 imageID 对应上述仓库摘要。沿用现有 PVC、SQLite 和原加密主密钥；按用户要求不做 PVC 备份。验证期间至多一个 FlinkDeployment，不修改两个既有 SQL 草稿。临时作业验证完按无状态停止、确认集群资源删除后清理本地草稿、SQL、Catalog 和迁移后的私有文件。

新 SQL 使用10MB/1归档双输出日志。JM/TM 日志代理已按角色、运维权限、路径白名单和 Secret 脱敏开放；现场通过代理读取成功。已有自定义或显式空日志不覆盖。新 Runner 的 SET/RESET/DDL 和函数注册能力需下载、上传并显式选择；既有共享 Runner 不自动替换。

本地 Parser 缓存曾落后于源代码协议，已按当前源码重建并更新仓库中的1.20.3/1.20.5制品，JDK8/17共32项协议检查通过。只读 Docker 验证的 SQLite 原生库需要 `/tmp` 可执行挂载（`--tmpfs /tmp:rw,exec,uid=10001,gid=10001`）；平台现有 Kubernetes emptyDir 不受此临时验证参数影响。

Hive 浏览的 SASL/TLS/Compact/Framed 和目标 Hive 服务未作实环境验收，不能把协议替身称为真实 Hive 验证；详细边界见 [Catalog 模块](agent/modules/catalog-workbench.md) 与 [验收](agent/validation.md#catalogudfsql与日志2026-10-25)。

2026-10-25收尾：最终镜像再次逐层核对并同步现场，两次现场用例为串行执行、任一时刻仅一个作业；最终 Runner 的作业也达到 RUNNING/STABLE。全部验证 FlinkDeployment/Pod、对应平台/SQL/Catalog与原始及迁移后的私有文件已清理。原两个用户SQL草稿及其修订不变。

新版 Runner 已作为独立共享文件 `artifacts/shared/sql-runner/flink-web-sql-runner-0.0.8-5fca445b.jar` 发布，SHA-256 `5fca445b1cf2272cf6e59ea6ec618322e2c2f054fe36b490750e2ad5d38bb301`，下载接口及 S3 下载哈希一致。使用新增 SET/DROP/UDF 前在“部署配置 → SQL Runner”明确选择 `flink-web-sql-runner-0.0.8-5fca445b.jar`；原默认共享 Runner 和已有选择保留，不静默覆盖。该文件是正式可选制品，清理临时资源时保留。

清理本任务3个Docker验证容器（含临时数据库卷）、独占网络、推送别名及3份未标记中间镜像；新增13项构建缓存中4项清除，9项正式镜像共享记录保留，原187项缓存均保留。仅删除本任务被取代的两份中间manifest，保留当前标签和共享blob，未对registry做全局GC。

## Pod诊断与证据边界修复（2026-10-26）

app/web沿用0.0.8，使用正式Dockerfile、干净Kylin运行基础和已验证离线制品构建；非root只读镜像完成测试后推送。指定LAN仓库已核对manifest正文/响应摘要、config、全部压缩层大小/哈希及解压diff_id。不更新latest，downloader和共享Runner/Parser保持。

| 镜像 | Manifest | Config / Image ID | 压缩层总字节 | 层数 |
| --- | --- | --- | ---: | ---: |
| app:0.0.8 | `sha256:ea8d57990cece64b41b3d4e4a5677e22546a73ad91e6921b3871fddf3bcde3b0` | `sha256:4017a2121c62b4a8d3fe78ecedfa610f0337d8b479bd320a5b21dc70ff67e959` | 283643933 | 11 |
| web:0.0.8 | `sha256:10e9b8c6518b192f523a0cabbf45616debda76c352a4799034a4ab39430ad537` | `sha256:b62f23b14dff9da61e453c2908c468033bd935c15cc9fbe8ccd8dd1c9fa517df` | 111359672 | 8 |

后端305通过/3条件跳过；最终Web17项相关回归及镜像内两份Parser实际调用通过，代理4类通过。完整范围见 [验收](agent/validation.md#pod诊断与证据边界修复2026-10-26)。用户确认集群关闭、先发布镜像，本轮未同步Kubernetes或启动Flink作业；集群恢复后需显式拉取新镜像并更新平台，页面才会生效。原PVC、数据库、主密钥保留，不备份PVC。

## 界面一致性与入口调整（2026-10-25）

app/web沿用0.0.8，平台API/前端版本同步；从正式Dockerfile、干净Kylin基础和当前通过检查的制品构建，不继承旧业务镜像。不改latest、downloader、Parser/Runner或既有连接数据。指定LAN仓库核对manifest响应/正文、config、全部压缩大小/哈希与解压diff_id后，平台StatefulSet重启完成，两个容器Ready且实际imageID匹配。

| 组件 | Manifest | Config | 层数 |
| --- | --- | --- | --- |
| app:0.0.8 | `sha256:415c29c10325a9bd7f953803ffcfaf0ea20b6a648b67bf74e21fbffcebc388a5` | `sha256:48a43519921e5d4d55bdc8ad1ba683270515ede6945f7e9ab123ff120414f3e6` | 11 |
| web:0.0.8 | `sha256:714550762778ed1eae7c1b9b4b6ec0e4e8aff23ef4ebbc7370718bfa5916aab0` | `sha256:db4306bd5f47eadfb68ba00786079082c27807d074f264d5701bb361a080ada0` | 8 |

前端最终只读镜像88项页面回归、4类代理验证通过；后端平台专项38项通过。独立临时数据库实际加密登录、JDBC连接/Catalog保存、SQL历史、TCP与中文审计通过。现场仅做只读页面验收；原SQL修订未变，未启动或重启Flink作业，PVC/数据库/主密钥未替换、不做PVC备份。入口与权限见 [功能说明](features.md#5-flink-catalog-与连接管理)。

本轮验证资源收尾：两独占容器（tmpfs数据库）、网络、推送别名、1份无标签中间镜像及预览进程已清理；末次新增缓存1条为正式镜像共享层，保留，未全局prune。独占临时文件删除，截图保留聊天可视化目录；原registry、正式标签和未知/既有缓存保留。
