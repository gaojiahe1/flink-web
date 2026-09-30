# Flink Web 部署指南

更新：2026-09-30。功能与权限规则见 [功能说明](features.md)。以下路径均相对项目根目录，示例凭据由部署者自行提供。

## 1. 部署拓扑与前置条件

~~~text
浏览器 → 节点 IP:30080 → 前端 Nginx
                         ├─ /api → Spring Boot → 元数据库 / Kubernetes API / S3
                         └─ /flink-ui/{jobId}/ → 后端鉴权代理 → Flink REST Service
~~~

前端镜像已经包含 Nginx，不必再增加独立 Nginx Deployment。Kubernetes Service 提供四层转发，不能替代按 URL 路径分流。前后端源码、构建产物与镜像分离。

部署前准备：

- 运维已安装 Operator/CRD；平台不负责安装、升级 Operator 或调整 Webhook。
- 一个作业 namespace、Flink 工作负载 ServiceAccount 及其 RBAC。平台通过 ServiceAccount 获取权限；可用 rbac.yaml 创建独立 flink-web 身份，或使用已授权的运维身份。
- Flink 运行镜像及匹配 JDK、Connector、Planner、S3/文件系统插件。
- 制品桶、状态存储目录及相应访问身份。
- SQLite 所需可写 PVC，或 MySQL 8+ 数据库及专用账号。

Flink UI 上游固定为 {FlinkDeployment资源名}-rest.{作业namespace}.svc:8081。代理只允许受控只读请求，不接收任意 URL，不转发平台 Cookie/Authorization；运行配置脱敏，环境与原始日志接口被阻止。不同 Flink UI 版本需实际验收。

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
| FLINK_WEB_STORAGE_TYPE | local（SQLite，默认）或 mysql |
| FLINK_WEB_LOCAL_PATH | SQLite 路径，./data/flink-web.db |
| FLINK_WEB_STORAGE_INITIALIZE | 本地库不存在时是否允许新建；应用与 Kubernetes 清单均为 true；不存在时建库，已有库不重建；可显式设 false 禁止创建缺失文件 |
| FLINK_WEB_MYSQL_URL / FLINK_WEB_MYSQL_USERNAME / FLINK_WEB_MYSQL_PASSWORD | MySQL JDBC 连接与专用账号 |
| FLINK_WEB_K8S_URL | Kubernetes API，默认 https://kubernetes.default.svc |
| FLINK_WEB_K8S_TOKEN_FILE / FLINK_WEB_K8S_CA_FILE | 默认 ServiceAccount token 与 ca.crt 挂载路径 |
| FLINK_WEB_OPERATOR_NAMESPACE | Operator 所在 namespace，默认同作业 namespace |
| FLINK_WEB_OPERATOR_DEPLOYMENT | 查询的 Deployment，默认 flink-kubernetes-operator |
| FLINK_WEB_OPERATOR_CONTAINER | 取镜像版本的容器名，默认 flink-kubernetes-operator |
| FLINK_WEB_WORKLOAD_SA | 新作业运行身份，默认 flink；不同于平台身份 |
| FLINK_WEB_STATE_ROOT | 页面 S3 状态目录建议值，默认 s3://flink-state；不再强制写入作业 |
| FLINK_WEB_S3_BUCKET / FLINK_WEB_S3_ENDPOINT | 制品桶及可选 S3 兼容端点 |
| FLINK_WEB_S3_PREFIX | 允许浏览的制品根前缀，默认 artifacts/ |
| AWS_REGION | 默认 us-east-1 |
| AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY | 平台 S3 SDK 默认凭证链可读取；也可配置工作负载身份 |
| FLINK_WEB_DOWNLOADER_IMAGE | 默认 flink-web-downloader:0.1.0 |
| FLINK_WEB_ARTIFACT_HOSTS | HTTP(S) 制品地址白名单，逗号分隔 host 或 host:port |
| FLINK_WEB_ARTIFACT_CREDENTIALS_SECRET | 下载器 S3 凭据 Secret，使用 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 键 |
| FLINK_WEB_SQL_VALIDATOR_DIR | Parser 根目录；本地默认 ./sql-validators，后端镜像内 /opt/sql-validators |

主密钥不能随重启重新生成；数据库中只有加密值，丢失主密钥无法恢复原密钥配置。

## 4. Kubernetes 与容器部署

从项目根目录构建并推送镜像：

~~~sh
docker build -f backend/Dockerfile -t YOUR/flink-web-backend:0.1.0 .
docker build -t YOUR/flink-web-frontend:0.1.0 frontend
docker build -t YOUR/flink-web-downloader:0.1.0 downloader
~~~

部署步骤：

1. 修改 [application.yaml](../deploy/kubernetes/application.yaml) 中的 namespace、后端/前端镜像、PVC 存储类及容量、状态目录、Operator 查询目标；将 FLINK_WEB_DOWNLOADER_IMAGE 指向已推送的下载器镜像。
2. 填写 application.yaml 中已合并的 flink-web-secrets（管理员密码与主密钥）。已有环境必须填回原主密钥；MinIO 凭据复用同 namespace 的 minio-secret。
3. 核对 application.yaml 所选 ServiceAccount 的 RBAC 后直接应用该文件；数据库不存在时自动创建，已有文件保留，无需额外初始化补丁或部署后的开关切换。当前文件使用已有 flink-operator 身份；若改用平台独立 flink-web 身份，先应用 [rbac.yaml](../deploy/kubernetes/rbac.yaml)。
4. Operator 在其他 namespace 时，修改并应用 [operator-rbac.yaml](../deploy/kubernetes/operator-rbac.yaml)；设置对应 FLINK_WEB_OPERATOR_NAMESPACE。Deployment 名变更时同步修改 Role 的 resourceNames。
5. 访问 http://NODE_IP:30080，在系统设置手动核对 Operator 版本、就绪副本和 Pod，再登记镜像、模板和配置，并在对象浏览器上传依赖文件。

### 当前环境：Secret、MinIO 与首次启动

当前仓库清单使用 namespace **flink-kubernetes-operator**，平台、MinIO Service 与 minio-secret 在同一 namespace；镜像仓库、local-storage、PVC 2Gi 和 NodePort 30080 保持环境原配置。部署前仍需核对实际集群；本地 YAML 存在不代表资源已创建。

envFrom.secretRef 只把**已经存在**的 Secret 的键值注入容器环境变量，不会创建 Secret，也不提供 Kubernetes API 权限。被引用的 Secret 必须与 Pod 同 namespace；修改环境变量 Secret 后需重新创建 Pod。[Kubernetes Secret 文档](https://kubernetes.io/docs/concepts/configuration/secret/)

| 配置或身份 | 用途 |
| --- | --- |
| flink-web-secrets / FLINK_WEB_ADMIN_PASSWORD | 首次创建平台管理员的密码，至少 12 字符、UTF-8 最多 72 字节；库已有用户时不覆盖账号 |
| flink-web-secrets / FLINK_WEB_MASTER_KEY | 加密数据库中的配置密钥；32 字节随机数的 Base64。它不是登录密码、S3 密码或临时传输公钥，必须长期保存，恢复数据库时使用原值 |
| minio-secret / MINIO_ROOT_USER、MINIO_ROOT_PASSWORD | 现有 MinIO 凭据，通过 secretKeyRef 映射为 AWS_ACCESS_KEY_ID、AWS_SECRET_ACCESS_KEY；生产可替换为仅允许所需桶的专用账号 |
| Deployment 的 ServiceAccount | 后端读取和修改 Kubernetes 资源所用的身份，与密码 Secret 无关；当前使用已有 flink-operator，rbac.yaml 提供可选的独立 flink-web 身份 |

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
kubectl -n flink-kubernetes-operator rollout status deployment/flink-web-backend --timeout=180s
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

SQLite 必须单副本并使用 Recreate 更新策略，PVC 需支持可靠的文件锁及 UID/GID 10001 写入。MySQL 模式可移除本地数据卷，但当前 Session 保存在进程内，后端仍按单副本部署。

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

2026-09-30：前后端须配套更新。新草稿分配自增编号，部署名称使用 flink-编号。历史资源纳管或数据库迁移时保持原名，先停止旧资源并确认删除，再启动时重建为编号名；平台展示名和 UUID 不变。停止可立即删除，也可先由 Operator 生成 Savepoint，用户手动刷新或再次停止时推进删除。停止意图和恢复路径落库，进程重启后可继续；不增加后台轮询。编号可能有间隔，不承诺连续。SQLite 迁移与编号重建由自动化测试及 Kubernetes 协议替身验证；MySQL 和真实 Operator 删除/恢复仍需部署环境验收。

全量 DDL 与增量脚本位于 [MySQL](../backend/src/main/resources/db/mysql/schema.sql) 和 [SQLite](../backend/src/main/resources/db/sqlite/schema.sql) 所在目录。已有数据库通过迁移升级，不要删除后重建；全量脚本的 CREATE IF NOT EXISTS 不能修复手工损坏的表。MySQL DDL 存在隐式提交，迁移失败应检查完成情况再恢复启动。

备份与切换：

- SQLite 停机后备份整个库文件，主密钥单独保管；恢复到可写 PVC 后使用原主密钥启动。
- MySQL 使用一致性备份，同时备份主密钥；恢复先验证迁移版本与用户登录。
- S3 制品、checkpoint/savepoint 与 Kubernetes Secret 不在元数据库备份内，分别备份。
- local/mysql 切换不会搬迁、双写或自动转换数据；需要独立迁移方案。GoldenDB 尚未适配。
- 当前无历史记录自动归档、主密钥自动轮换或旧 Secret 自动清理；规划长期容量与维护流程。

## 6. SQL Runner、Parser 与运行镜像

**Runner 运行作业，Parser 校验语法，两者不能互相替代。**

Runner 默认使用 Flink 1.20.3 构建，运行产物面向 Java 11+，平台后端使用 JDK 17。按实际 Flink 版本构建并验收，不把默认构建当作 1.16–2.3 全版本认证：

~~~sh
mvn -f sql-runner/pom.xml -Dflink.version=1.20.3 package
docker build --build-arg FLINK_IMAGE=YOUR/flink:1.20.3 -t YOUR/flink-sql:1.20.3 sql-runner
~~~

[Runner Dockerfile](../sql-runner/Dockerfile) 将 JAR 安装到 /opt/flink/lib/flink-web-sql-runner.jar；SQL 页面默认使用 local:///opt/flink/lib/flink-web-sql-runner.jar。运行镜像需自行提供 Table API、Planner、所需 Connector、数据库驱动和文件系统插件，示例 datagen/print 也需要相应依赖。使用 Catalog 的镜像须包含当前 Runner。

后端镜像已内置 Flink 1.20.3 / Calcite 1.32.0 Parser。本地可安装：

~~~powershell
mvn -f sql-validator/pom.xml package
New-Item -ItemType Directory -Force sql-validators/1.20.3
Copy-Item sql-validator/target/flink-web-sql-validator.jar sql-validators/1.20.3/validator.jar
$env:FLINK_WEB_SQL_VALIDATOR_DIR = (Resolve-Path sql-validators).Path
~~~

镜像登记的 flinkVersion 必须为匹配的精确补丁版本，未安装对应适配器时返回 BASIC。其他版本需核对 Flink/Calcite 依赖后独立构建，不能只改目录名；以只读卷装入目录时不要遮蔽需保留的默认版本。每次校验使用受限独立 JVM，不继承平台数据库/S3 凭据，不加载用户连接器或探测 Catalog。

## 7. S3、下载器与状态存储

必须区分三种身份：

| 使用方 | 职责与配置 |
| --- | --- |
| 平台后端 | 制品上传/浏览/下载及快照路径检查，使用平台 S3 配置和凭据链 |
| initContainer 下载器 | 启动前获取制品，使用工作负载凭据或指定的下载器 Secret |
| Flink 作业 | checkpoint/savepoint、Catalog/文件系统访问，使用运行镜像的插件与 PodTemplate/工作负载身份 |

平台不会自动把后端 S3 凭据复制给 Flink。Secret、镜像拉取凭据和挂载配置必须位于作业可访问的位置。

initContainer 在 JM/TM 分别下载 HTTP(S)/S3 文件并校验 SHA-256；超时、大小超限或校验失败阻止启动。HTTP(S) 需在白名单内，禁止重定向，Bearer Token 必须走 HTTPS。

制品卷挂载 /opt/flink/usrlib，会遮蔽镜像原目录。依赖下载启用时，把所需用户 JAR 全部列入下载清单；预置主 JAR 可放在未被遮蔽的路径。Flink 核心、Planner、S3 插件保留在运行镜像，不当作普通用户依赖替换。

状态存储在 JAR/SQL 页面显式开启；启用后默认使用 S3，页面用 FLINK_WEB_STATE_ROOT 和作业 UUID 自动生成 checkpoint/savepoint 路径并写入 Flink 配置，同时写入非敏感连接参数；这些字段不再单独展示为表单输入，必要时可在 Flink 配置中调整。后台不再强制周期、保留数量或目录。关闭存储会移除表单管理的 Checkpoint 相关配置。Flink 镜像必须包含对应文件系统插件并获得工作负载凭据；平台 S3 SDK 可访问不表示 Flink 可以访问。last-state 仅保留为升级策略，需要 Kubernetes HA 与 HA 存储，不再作为停止选项。指定路径的手动快照检查仍使用平台 S3 endpoint/凭据并限制到配置的桶，不证明恢复兼容性。

对象浏览器限定在 FLINK_WEB_S3_PREFIX 下，依赖默认位于 jobs/{作业 UUID}/，可选择 shared/。上传直接写入 S3，拒绝同名覆盖；不需要制品登记。可创建目录标记、浏览、下载和删除具体文件，不做递归清理。S3 身份除 List/Get/Put 外，删除文件需要 DeleteObject 权限；条件写入需要对象存储支持 If-None-Match。外部上传没有 sha256 元数据的文件在引用时流式计算校验和，大文件会增加预览等待时间，最大 1 GiB。保留旧登记记录用于已有下载清单兼容。

## 8. 排障与验收

| 现象 | 优先检查 |
| --- | --- |
| 初始管理员密码校验失败 | 修改 flink-web-secrets 的 FLINK_WEB_ADMIN_PASSWORD，至少 12 字符且 UTF-8 不超过 72 字节，再重启后端；如已有用户却进入初始化，先核对 PVC/库路径 |
| INVALID_CREDENTIAL_ENVELOPE | 前后端是否同步升级、Cookie 会话是否一致、挑战是否过期或已消费；重新获取挑战，不改为明文重试 |
| 本地数据库启动被拒绝 | 路径、PVC 权限、实例锁、INITIALIZE 开关；不要通过开启初始化掩盖挂错卷 |
| JOB_NAME_EXISTS / DATA_CONFLICT | 作业重名或数据约束；修改名称或编辑已有记录 |
| STORAGE_BUSY / STORAGE_ERROR | 写入竞争、文件系统与数据库配置；查看安全日志中的 SQLState/vendorCode，不输出 YAML 或密钥 |
| REVISION_CONFLICT / 身份冲突 | 重新读取草稿及运行资源；核对 resourceVersion、绑定 UID 和同名资源是否被重建 |
| Operator 未找到/版本未知 | 查询 namespace、Deployment/容器名、镜像 tag 与 RBAC；自定义或未来版本不自动猜测 |
| Operator 有版本但无 Pod 状态 | 查看 podMessage、Pod list 权限、Deployment selector 与实际副本 |
| Kubernetes 拒绝或字段被裁剪 | CRD/Operator 版本、RBAC、字段支持、工作负载 SA；dry-run 成功也不代表作业运行成功 |
| SQL 只有 BASIC 检查 | 精确 flinkVersion 与 Parser 目录是否匹配 |
| SQL 运行失败 | Runner、Planner、Connector、Catalog 插件、字段语义和远端服务；从诊断页按需读取日志 |
| initContainer / S3 失败 | 白名单、URI、SHA-256、文件名、工作负载凭据、插件与卷遮蔽 |
| Flink UI 不可用 | 作业权限、REST Service 名和 8081 端口、代理前缀及版本兼容性 |
| 操作结果 UNKNOWN | 先手动查询，确认集群是否已接受请求，避免用新 requestId 盲目重复提交 |

健康检查为 /actuator/health。页面写请求使用 Session + CSRF；元数据修改带 revision，运行操作带 UUID requestId。同一 requestId 具有幂等语义，不能用历史 ACCEPTED 推断当前状态。

上线时实际验证：登录/权限、既有作业只登记纳管、JAR 与 SQL 部署、3 秒刷新和 YAML 零额外请求、Operator 查询、停止与删除保护、真实 savepoint/checkpoint 恢复、同源 Flink UI，以及数据库/PVC 备份恢复。HTTP 协议替身和浏览器回归不能替代这些检查。Kubernetes 最低可用版本取决于运维安装的 Operator/CRD，不承诺所有 Kubernetes 1.20+ 组合均可运行。

## 9. 维护入口

~~~text
backend/        API、业务、权限、持久化、Kubernetes/S3 客户端
frontend/       Vue 业务页面、共享组件、Vite 与 Nginx
sql-runner/     Flink SQL Application 入口与运行镜像
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

功能、参数或行为变化时直接更新这两份文档，不另建按轮次命名的升级报告、聊天缓存或截图记录。版本筛选数据及出处维护在 [operator-compatibility.json](../backend/src/main/resources/operator-compatibility.json)，数据库结构以增量 SQL 为准。
