# Flink Web 部署指南

更新：2026-10-08。功能与权限规则见 [功能说明](features.md)。以下路径均相对项目根目录，示例凭据由部署者自行提供。

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
| AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY | 平台 S3 SDK 默认凭证链可读取；启动时成对加密导入配置管理 s3-access-key / s3-secret-key，仅初始化缺失项，不覆盖已保存值 |
| FLINK_WEB_DOWNLOADER_IMAGE | 统一下载器绑定的初始值，默认空；系统设置保存的单例绑定优先 |
| FLINK_WEB_ARTIFACT_HOSTS | HTTP(S) 制品地址白名单，逗号分隔 host 或 host:port |
| FLINK_WEB_ARTIFACT_CREDENTIALS_SECRET | 可选已有下载器 S3 Secret，使用 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 键；未配置则引用配置管理的 s3-access-key / s3-secret-key |
| FLINK_WEB_SQL_VALIDATOR_DIR | Parser 根目录；本地默认 ./sql-validators，后端镜像内 /opt/sql-validators |

主密钥不能随重启重新生成；数据库中只有加密值，丢失主密钥无法恢复原密钥配置。

## 4. Kubernetes 与容器部署

2026-10-01：后端、前端、下载器和 SQL Runner 的最终运行层统一使用 Kylin V10 SP3，默认 KYLIN_BASE=macrosan/kylin:v10-sp3-2403（[MacroSAN 维护的公共镜像](https://hub.docker.com/r/macrosan/kylin)，并非麒麟官方仓库）。每个 Dockerfile 都支持通过 --build-arg KYLIN_BASE=内网仓库/镜像:SP3或更高版本 替换，基础镜像需提供兼容的 yum 软件源。

构建阶段仍使用 Maven/Node 工具镜像；后端运行层复制 Temurin 17 JRE，SQL Runner 复制 Temurin 17 JDK 和所选 Flink 分发文件。前端使用 Kylin 软件源的 Nginx，以非 root 身份监听 8080，临时文件写 /tmp。下载器在同一 Kylin 基础层编译 Python 3.12.15（[源码及校验和](https://www.python.org/downloads/release/python-31215/)），安装固定 boto3 依赖，避免使用 SP3 软件源中较旧的 Python 3.7；替换 Python 版本时须同步校验和。构建需要能够访问软件源，或准备相应内网镜像源。

从项目根目录构建镜像，再按部署流程推送：

~~~sh
docker build -f backend/Dockerfile -t YOUR/flink-web-backend:0.1.0 .
docker build -t YOUR/flink-web-frontend:0.1.0 frontend
docker build -t YOUR/flink-web-downloader:0.1.0 downloader
~~~

2026-10-05 发布验证：Docker Desktop linux/amd64 下 app、web、downloader 三个 Kylin SP3 镜像已重新构建并推送到 192.168.68.1:5000/flink-web，按用户要求覆盖 0.0.4 标签，不更新 latest，仓库摘要与本地一致。后端使用临时 SQLite 健康检查 UP，前端 HTTP 200、Nginx 配置及接口代理通过；下载器以非 root、只读根文件系统准备启动脚本；实际 Flink 1.20.5 主容器从自身镜像复制 Presto 插件并启动，/overview 返回 200。本地 MinIO 整目录复制与删除验证通过。已清理本次临时验证容器和网络，推送后清空 Docker 构建缓存，回收 2.505 GB，保留已标记镜像。未应用实际 Kubernetes 清单或修改运行作业；现场 Metaspace 故障需按下方说明调整作业内存并重新部署。SQL Runner 本次未发布。

2026-10-08 发布更新：Savepoint 后停止修复已重新构建并覆盖推送 app:0.0.4（linux/amd64、Kylin SP3），仓库摘要与本地一致；web/downloader 沿用 2026-10-05 镜像，已复核仓库摘要。后端 120 项回归通过，1 项可选 MinIO 用例跳过；镜像以非 root、只读根文件系统和临时 SQLite 启动，健康接口返回 UP。临时验证容器已删除，构建缓存清空并回收 570 MB。未更新 Kubernetes 工作负载，已有 Pod 需重新创建才会拉取新镜像。

| 镜像（标签 0.0.4） | 仓库摘要 |
| --- | --- |
| 192.168.68.1:5000/flink-web/app | sha256:bb214b02a2d316712bfb86acadf3ed9e6001a7810755b3bfda8c15637c90b7e6 |
| 192.168.68.1:5000/flink-web/downloader | sha256:7140951cc61de1dd2951155940d91f8d099e398b65b52c5ba37e516eb070a687 |
| 192.168.68.1:5000/flink-web/web | sha256:8d85c76a317ff160dc32048d5907eab1c0131c99e34d9e8ea640708869a41557 |


本次复用 `0.0.4` 标签。主清单已给 app/web 设置 `imagePullPolicy: Always`；应用清单后仍需重建已有 Pod 才会拉取。新生成的下载器同样使用 Always，已有作业须重新提交配置并重建 Pod；也可以在系统设置中绑定 `192.168.68.1:5000/flink-web/downloader@sha256:7140951cc61de1dd2951155940d91f8d099e398b65b52c5ba37e516eb070a687`。已有运行中的作业不会随仓库标签更新自动变更。

部署步骤：

1. 修改 [application.yaml](../deploy/kubernetes/application.yaml) 中的 namespace、后端/前端镜像、PVC 存储类及容量、状态目录、Operator 查询目标；在系统设置中统一绑定已推送的下载器镜像完整地址；首次未保存时使用 FLINK_WEB_DOWNLOADER_IMAGE，清单初始值为 192.168.68.1:5000/flink-web/downloader:0.0.4。
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

Runner 默认使用 Flink 1.20.3 构建，运行产物面向 Java 11+，平台后端使用 JDK 17。按实际 Flink 版本构建并验收，不把默认构建当作 1.16–2.3 全版本认证：

~~~sh
mvn -f sql-runner/pom.xml -Dflink.version=1.20.3 package
docker build --build-arg FLINK_IMAGE=YOUR/flink:1.20.3 -t YOUR/flink-sql:1.20.3 sql-runner
~~~

[Runner Dockerfile](../sql-runner/Dockerfile) 将 JAR 安装到 /opt/flink/lib/flink-web-sql-runner.jar；SQL 页面默认使用 local:///opt/flink/lib/flink-web-sql-runner.jar。FLINK_IMAGE 构建参数只提供 Flink 分发文件，最终操作系统仍由 KYLIN_BASE 决定；不会继承该分发镜像的额外系统软件或自定义启动环境。运行镜像需自行提供 Table API、Planner、所需 Connector、数据库驱动和文件系统插件，示例 datagen/print 也需要相应依赖。使用 Catalog 的镜像须包含当前 Runner。

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

2026-09-30：平台启动时将显式环境变量 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 加密导入配置管理（s3-access-key / s3-secret-key），不读取其他凭据链来源来持久化。已有记录不覆盖；凭据轮换请在配置管理中更新。启用状态存储时，源配置的 s3.access-key / s3.secret-key 自动引用这两个变量，部署时转为主容器 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 的 Kubernetes Secret 引用，并从实际 flinkConfiguration 中移除这两个占位值。凭据放在共享 spec.podTemplate；预览、发布历史不包含密钥明文。镜像 S3 插件必须支持默认 AWS 环境凭据链；不再自动生成 s3.region。平台 S3 SDK 自身仍使用 AWS_REGION。无环境凭据时，可由管理员手动建立同名密钥配置或已有 Secret 引用；其他工作负载身份可自行在 YAML 中配置。Secret、镜像拉取凭据和挂载配置必须位于作业可访问的位置。

2026-10-01：initContainer 在共享 spec.podTemplate 中定义一次，由 Operator 应用于 JM/TM；不再校验下载文件 SHA-256，超时、大小超限或下载失败阻止启动。HTTP(S) 需在白名单内，禁止重定向，Bearer Token 必须走 HTTPS。

2026-10-01：S3 文件下载按需生成 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 的 secretKeyRef，不需要 AWS_DEFAULT_REGION。即使作业关闭状态存储，S3 制品仍需要下载凭据；纯 HTTP 下载不添加 S3 变量。配置管理密钥会物化为同 namespace 的不可变 Secret；引用已有 Secret 时不创建副本。修改全局镜像后，预览及提交会更新旧 fw-download 镜像、清理历史校验字段和区域变量；运行中资源不自动更新。共享模板结构参考 [Operator 官方示例](https://github.com/apache/flink-kubernetes-operator/blob/main/examples/basic-checkpoint-ha.yaml)。

制品卷挂载 /opt/flink/usrlib，会遮蔽镜像原目录。依赖下载启用时，把所需用户 JAR 全部列入下载清单；预置主 JAR 可放在未被遮蔽的路径。Flink 核心、Planner 保留在运行镜像。Presto S3 插件由主容器启动脚本从自身镜像 opt 目录复制到独立插件卷，不作为普通 usrlib 依赖；不能同时手动将同一插件放入 lib/usrlib。

状态存储在 JAR/SQL 页面显式开启；启用后默认使用 S3，页面用 FLINK_WEB_STATE_ROOT 和作业 UUID 自动生成 checkpoint/savepoint 路径并写入 Flink 配置，同时写入连接参数和配置管理中的密钥引用；这些字段不再单独展示为表单输入，必要时可在 Flink 配置中调整。后台不再强制周期、保留数量或目录。Checkpoint 外部存储保留策略由页面选择：默认保留，也可选择取消后删除、不保留外部 Checkpoint 或 Flink 默认。立即删除 FlinkDeployment 可能来不及正常执行取消清理，需检查残留对象；Savepoint 的保留不受该策略影响。关闭存储会移除表单管理的 Checkpoint 相关配置。2026-10-04 起仅使用一个 downloader initContainer 下载程序并准备 /flink-bootstrap/start-flink.sh。主容器的 kubernetes.entry.path 指向此脚本，启动时从本镜像 /opt/flink/opt 自动发现唯一 flink-s3-fs-presto-*.jar，复制到可写 emptyDir（/opt/flink/plugins/s3-fs-presto/），再执行原入口并透传参数。无需在 S3 保存插件，也不依赖镜像 tag 中的补丁版本。镜像须提供 sh、cp、mkdir、rm 和默认 /docker-entrypoint.sh；自定义 kubernetes.entry.path 会保存在 FLINK_WEB_ORIGINAL_ENTRYPOINT 并继续调用，禁用后恢复。仅启用插件时不会遮蔽 /opt/flink/usrlib；主容器需要插件卷写权限。缺失/多个插件会在启动日志中明确报错。所有 S3 程序下载仍需工作负载 Secret 凭据；平台 S3 SDK 可访问不表示 Flink 可以访问。入口配置依据 [Flink 1.20 Kubernetes 配置](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/config/#kubernetes-entry-path)。last-state 仅保留为升级策略，需要 Kubernetes HA 与 HA 存储，不再作为停止选项。指定路径的手动快照检查仍使用平台 S3 endpoint/凭据并限制到配置的桶，不证明恢复兼容性。

2026-10-02：删除平台作业会递归清理 FLINK_WEB_S3_PREFIX/jobs/{UUID}/ 与 FLINK_WEB_STATE_ROOT/{UUID}/；只对平台分配的 UUID 前缀执行删除，要求对象存储身份具备两个桶相应前缀的 ListBucket / DeleteObject 权限。未配置 S3 且草稿无 S3 引用时无需清理；有 S3 引用但未配置时拒绝删除。共享、自定义外部目录不自动删除。版本化桶的历史版本由生命周期策略管理。复制使用同桶 CopyObject（源 GetObject、目标 PutObject），带源 ETag 条件校验，分页复制专属文件，不复制状态数据。清理失败保留平台记录供重试，但已成功删除的对象无法恢复。

对象浏览器限定在 FLINK_WEB_S3_PREFIX 下，依赖默认位于 jobs/{作业 UUID}/，可选择 shared/。上传直接写入 S3，拒绝同名覆盖；不需要制品登记。可创建目录标记、浏览、下载和删除具体文件，不做递归清理。S3 身份除 List/Get/Put 外，删除文件需要 DeleteObject 权限；条件写入需要对象存储支持 If-None-Match。引用对象时仅 HEAD 查询存在性和大小，最大 1 GiB；不再为预览下载文件计算校验和。上传仍可记录内容摘要元数据，但不用于 initContainer 校验。保留旧登记记录用于已有下载清单兼容。

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
| initContainer / S3 失败 | 统一下载器绑定、白名单、URI、文件名、Secret 引用、插件与卷遮蔽 |
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

2026-10-02：S3 插件初始化结构依据 [Flink S3 文件系统说明](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/filesystems/s3/)。本地 Flink 1.20.5 镜像已验证非 root 读取和复制实际插件 JAR；共享 PodTemplate、重复渲染、关闭清理和挂载冲突通过自动化测试，尚未在实际 Kubernetes 集群验收本次新增功能。


2026-10-04 编辑来源保护：新 JAR 表单写入 flink-web/editor=jar-v1；纳管作业标记 yaml，未标记旧作业默认 YAML 编辑。用户可在确认配置可能改变后尝试切换 JAR 表单，复制时也可选择编辑方式。解析失败保留 YAML；普通改标签不会解除限制。此标记用于防止误覆盖，不作为 Kubernetes 权限认证。

2026-10-04 故障说明：提供的部署快照显示 flink-main-container CrashLoopBackOff，同时旧清单曾把 S3 插件下载到 usrlib 并额外初始化到 plugins。本次消除双重插件加载路径和两个托管 initContainer；仅凭 CR 状态不能确定主容器退出根因，实际问题仍需结合 Pod 上一实例日志、退出码、Secret 与卷事件排查。插件目录要求依据 [Flink 1.20 S3 文档](https://nightlies.apache.org/flink/flink-docs-release-1.20/docs/deployment/filesystems/s3/)。


2026-10-04 Flink UI：现场 Pod 内直连 /overview 返回 Internal server error: Metaspace，已确认故障来自 JobManager 类元数据内存，而非平台代理。建议先把 jobmanager.memory.jvm-metaspace.size 调为 512m，JobManager 总内存调为 2048m，按状态保留策略重新部署；仅增加总内存不会自动提高 Metaspace 上限。JAR 表单显示 JM/TM Metaspace 并回填旧值；新建无模板作业默认 JM 总内存 2048m、Metaspace 512m，已有 YAML/模板显式值不覆盖。仍失败需排查类加载器和实际内存需求。代理保留原路径及错误状态；入口遇到明确 Metaspace 响应展示修复说明，其他上游 4xx/5xx 记录作业 ID、部署名、路径和状态码，连接错误记录异常类型，不记录凭据或查询参数。依据 [Flink 内存排障](https://nightlies.apache.org/flink/flink-docs-release-1.20/zh/docs/deployment/memory/mem_trouble/)。


2026-10-08 Savepoint 停止排查：先确认作业正常 RUNNING、Savepoint 目录及访问权限可用，且没有升级中的配置。平台提交 savepoint 挂起并禁用 last-state 回退；Operator 完成后，手动刷新推进删除。新版 Operator 使用 FlinkStateSnapshot 时，平台需具有既有 RBAC 中的 flinkstatesnapshots 查询权限，且快照需完成并标记 UPGRADE、路径匹配；仅出现 upgradeSavepointPath 不能证明是 Savepoint，因为终态 Checkpoint 观测也会写入该字段。参考 [Operator SnapshotObserver](https://github.com/apache/flink-kubernetes-operator/blob/release-1.13/flink-kubernetes-operator/src/main/java/org/apache/flink/kubernetes/operator/observer/SnapshotObserver.java) 与 [FlinkStateSnapshotUtils](https://github.com/apache/flink-kubernetes-operator/blob/release-1.13/flink-kubernetes-operator/src/main/java/org/apache/flink/kubernetes/operator/utils/FlinkStateSnapshotUtils.java)。出现“未确认保存点”时检查快照状态及 Operator 日志；排查前不要删除保存点目录。代码回归使用 Kubernetes 替身，仍需实际集群验收保存点生成、删除和再次启动恢复。

2026-10-08 容器检查说明：SQLite JDBC 需要从临时目录加载本地库。使用只读根文件系统时需提供可写且允许加载本地库的临时目录；本地 Docker 检查使用 /tmp 的 tmpfs，并显式设置 exec。noexec 挂载会导致本地库加载失败，此限制与 Savepoint 修复无关。
