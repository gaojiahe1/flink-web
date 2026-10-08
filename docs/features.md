# Flink Web 功能说明

更新：2026-10-08。本文说明当前实现与必须保留的业务规则；安装、环境变量和故障处理见 [部署指南](deployment.md)。

## 1. 平台定位与规则

Flink Web 使用 Spring Boot / JDK 17 和 Vue 3 / TypeScript，通过运维已部署的 Flink Kubernetes Operator 管理固定 namespace 中的 Application 作业。平台维护 FlinkDeployment 声明，Operator 负责实际协调；平台不安装 Operator，不修改其 Webhook。

- **按需查询**：首次进入作业列表查询一次，其后手动刷新，前端冷却 3 秒。多用户、多标签独立请求，没有后台轮询、watch、跨页面状态缓存或请求合并。提交和纳管时会进行必要的即时身份/状态检查。
- **状态语义**：草稿、期望状态和最近观测状态分别展示。操作返回 ACCEPTED 仅表示请求已受理，不代表作业运行成功；最终状态以手动刷新结果为准。JobManager ERROR、status.error 或失败状态优先显示为“部署异常”，保留原始 Flink 状态供 YAML/诊断查看；列表同时展示错误摘要。
- **停止与删除**：停止最终删除 Kubernetes FlinkDeployment。支持“Savepoint 完成后删除”和“立即删除”，不提供 last-state 停止。前者要求正常 RUNNING 且已配置 Savepoint 目录，先由 Operator 挂起并生成新的保存点，持久化恢复路径后删除资源；手动刷新或再次停止会推进这个已授权的停止流程，后台不轮询，保存点未完成不删除。后者直接提交带 UID/resourceVersion 前置条件的删除，适用于启动中或异常作业。删除完成前停止仍可用，可从保留状态切换到立即删除。启动受理后禁用重复启动；快照只允许实际 RUNNING、期望 running、没有启停或升级中的作业。停止提交后立即显示停止意图；刷新先推进停止再采集状态，清除旧的提交提示，确认资源消失后显示已停止。停止提交后关闭弹窗，结果显示在页面，不再展示第二个结果弹窗。平台草稿仍须等集群资源消失后才能删除。停止前的实际配置同时落库，再次启动复用该配置，避免误发布未部署的编辑草稿；运维角色可以按原配置重建，首次部署仍需要编辑权限。
- **存储**：默认本地 SQLite，可挂 PVC；可切换 MySQL。切换配置不会迁移已有数据。当前按单后端实例部署。默认自动判断数据库文件：不存在时建库，已存在时保留数据并执行必要版本迁移，无需首次部署补丁或切换开关；启动报告区分本地库缺失与初始管理员密码配置错误，给出修复步骤。S3 不存储平台元数据库。
- **范围**：不管理多集群、多作业 namespace、Session 集群或 FlinkSessionJob。Operator 可以位于另一个已明确配置并授权的 namespace。

## 2. 作业管理

| 能力 | 使用方式与边界 |
| --- | --- |
| 列表与筛选 | 按名称、描述、状态、Flink 版本筛选，查看最近查询时间；支持批量启动、停止和快照操作；页面顶部仅显示进行中的任务，超出一行时横向滚动，完成和失败结果在作业行及记录中查看 |
| 既有作业纳管 | 管理员确认后登记已有 Application 资源，保留原 spec，以 UID 绑定；纳管不修改集群、不重启作业。同名冲突、旧 UID、过期 resourceVersion 或正在删除的资源会被拒绝 |
| 当前 YAML | 每次刷新同时返回当前 FlinkDeployment 的只读、块状缩进 YAML；点击“当前 YAML”只打开本页结果，不再次请求；带语法高亮、行号与复制按钮，复制内容与当前展示的脱敏快照一致。刷新失败清空结果 |
| 编辑与发布 | 保存草稿只写元数据；部署时校验 YAML、渲染变量、执行 Kubernetes dry-run，再提交资源，避免字段被 CRD 静默裁剪 |
| 发布版本 | 固定提交内容，按行对比并恢复为新草稿；恢复不自动部署、不回滚运行状态或历史密钥。实际提交失败时仍可能有发布记录 |
| 诊断与日志 | 作业行“Pod 状态”打开右侧抽屉，查询节点、Pod 条件、初始化/运行容器、重启次数、退出码与最近事件；详情页可查询受限长度容器日志及上一实例日志；验证 Pod 所属关系，不跟随日志、不保存原始日志 |
| 快照与恢复 | 手动触发 savepoint/checkpoint，查看已观测快照，检查 S3 路径或选择指定路径恢复 |
| Flink UI | 从作业行打开同源只读代理，与平台共用 IP:NodePort；受登录、角色与作业权限控制 |
| 审计与操作记录 | 操作记录与发布版本、诊断与日志等共用详情页签，默认打开操作记录；“刷新记录”同步该页签。写操作记录审计，运行操作使用 requestId 幂等；UNKNOWN 表示结果不确定，应先查询再决定是否重试 |

运行 YAML 的 metadata/spec 中的密钥、环境变量字面值、命令参数及注解会隐藏，managedFields 不返回；按 2026-09-29 的展示要求，顶层 status 及其全部子字段保持原值（包括 error、lastReconciledSpec），不作脱敏替换。该副本不能直接用于重新部署。日志会遮蔽已知密钥与常见凭据模式，但不能保证识别任意应用输出中的所有敏感内容。

状态存储由 JAR/SQL 页面显式开启；后台不再补入 checkpoint 间隔、S3 目录或保留数量。按 2026-09-30 最新要求，启用后默认使用 S3，自动将 endpoint、路径式访问、按作业 UUID 划分的 Checkpoint/Savepoint 路径写入 Flink 配置，同时生成 high-availability.type=kubernetes 与按作业 UUID 划分的 high-availability.storageDir（/ha）；关闭状态存储时移除托管的 Checkpoint/Savepoint、S3 和 HA 配置。不再生成 s3.region；表单不再要求填写这些连接项目，也不再展示存储实现或状态后端选择。页面提供 Checkpoint 周期、保留数量、外部 Checkpoint 存储策略（取消后保留、取消后删除、不保留外部 Checkpoint、Flink 默认）以及恢复策略的对应参数，提供各策略的介绍和适用场景；这些值与 Flink 配置编辑器共用同一份配置。启用时写入 s3.access-key / s3.secret-key，分别引用配置管理中的 {{secret:s3-access-key}} / {{secret:s3-secret-key}}；部署渲染时将这两个引用转换为 Flink 主容器的 AWS Secret 环境变量，不把密钥明文写入 CR、预览或发布历史。管理员需保留运行镜像的默认 S3 凭据链。所有表单时长采用数字与单位分离的输入，旧配置单位回填保留语义。Flink 1.x/2.x 按版本生成对应配置键，并展示已有旧键的值。纳管不改变既有状态目录与保留策略。快照台账只记录用户刷新时观测到的完成点，不补造遗漏记录；_metadata 存在不等于共享状态完整或升级兼容。last-state 校验同时要求 HA 类型及 HA 存储目录，运行身份还需具备 Kubernetes HA 所需权限。

2026-09-29：作业列表展示数据库自增编号；新作业 FlinkDeployment 名称为至少七位补零编号（flink-0000001 起，超过七位继续增长），作业显示名称仍独立且唯一。UUID 继续用于平台 API/权限关联；编号删除后不复用。升级自动为已有作业补编号；纳管和数据库升级本身保留原资源名及 UID。按 2026-09-30 决策，旧资源停止并确认删除后，再次启动会切换到 flink-0000001 形式的编号；展示名只用于平台，平台 UUID 与编号保持稳定。旧资源尚在时须先停止，不能同时创建第二份。名称输入失焦查询全局重名，保存时由数据库唯一约束兜底。列表展开区已移除重复的部署/启停/指定路径恢复和原始快照信息，保留操作记录及工作台。

页面不提供重启按钮；停止与启动为明确的独立操作。2026-10-02：删除平台作业会同步清理受管根目录下 jobs/{UUID}/ 的全部文件，以及状态根目录下 {UUID}/ 的 Checkpoint、Savepoint 和 HA 文件。按精确带斜杠前缀分页清理，保留共享目录、其他作业目录、手工外部目录和历史 Secret；停止本身仍保留目录。S3 清理失败会回滚草稿删除，保留记录供重试；已删除对象无法回滚。版本化桶仅删除当前对象，历史版本由存储生命周期策略管理。

## 3. JAR、YAML、模板与镜像

从“新建 JAR 作业”进入，内部可切换 JAR 表单与 YAML 编辑。

1. 填写作业名称与描述，选择镜像及可选模板。
2. 选择或上传主 JAR，单独选择附加依赖/配置文件；主包与依赖不能重复，文件名不能冲突。
3. 填写入口类、程序参数、并行度、Slot、JM/TM 内存和 CPU。程序参数每行对应一个 argv，保留参数内空格，不作 shell 拆分。
4. 配置 Flink 参数与 PodTemplate，验证并预览。
5. 保存草稿，或显式保存并部署。部署请求被接受后跳转作业列表；拒绝或未知结果留在编辑页展示信息，不能把受理状态当成运行成功。

2026-10-04：新表单生成 YAML 标记 metadata.labels.flink-web/editor=jar-v1；纳管作业标为 yaml，无标记旧作业默认使用 YAML。旧作业可以确认“表单可能改变原 YAML”后尝试回填；复制弹窗可选择 YAML 或 JAR 表单，选择表单会先提示转换影响。确认通过显式 allowFormRewrite 参数传给后端，普通修改标签不解除保护。成功保存转换后保留 jar-v1 标记。回填镜像、主包/依赖、参数、并行度、Slot、JM/TM 资源及全部 Flink 配置，生成时保留未由表单管理的字段。语法错误、未知镜像、不同的 JM/TM 下载配置、无法表达的参数或不在当前 S3 范围内的依赖仍阻止切换，并保留原 YAML。

Flink 配置支持逐行 key:value、常用键提示、中文说明、重复键与格式检查。YAML 编辑器保留注释，选择镜像时同步 spec.image 与 spec.flinkVersion。

模板可以包含完整 FlinkDeployment 和 PodTemplate，包括标签、环境变量、卷及 initContainers。SQL 高级配置可以从模板复制 PodTemplate；复制后独立编辑，不随模板自动更新。

2026-09-29：镜像登记表单保留唯一标识、描述、仓库、tag 和可选 JDK 说明，移除显示名称、Flink 版本与 imagePullSecret 输入。Flink 版本从 tag 自动提取（例如 1.20.5-scala_2.12 → 1.20.5），无法识别时在版本字段提示；旧记录原有拉取 Secret 继续保留。2026-10-01：文件下载器与 Flink 镜像独立，在系统设置统一绑定单个完整镜像地址，仅管理员可修改并校验修订号；所有作业共用该设置。未保存设置时读取 FLINK_WEB_DOWNLOADER_IMAGE 初始值，忽略历史 Flink 镜像记录中的 downloaderImage。预览、部署、停止后重建都会刷新平台托管的 fw-download 镜像；不自动修改已运行资源。未选择外部文件且不需要 S3 插件时不要求下载器镜像。选择框收起显示 Flink 版本，展开显示 tag 和名称。平台不登记或生成镜像 digest。

所有页面的浏览器 confirm 已替换为站内确认框，支持取消与 Escape；输入约束失败在对应字段显示红框和行内提示，编辑器格式错误同样标红。网络/权限等非输入错误继续作为页面提示。修改字段或切换策略时清除本表单已过期的服务端校验边框；JAR/SQL 表单同时清除旧验证结果与预览，配置管理修改后清除旧提交提示。

## 4. SQL 开发

SQL 工作台提供可折叠、独立滚动的草稿列表、编辑器及部署配置。支持保存、修订控制、历史对比/恢复、SQL 检查、YAML 预览和发布为 Application 作业。

- 支持基础 CREATE CATALOG / DATABASE、USE CATALOG / DATABASE、CREATE TABLE / VIEW 和 INSERT INTO / OVERWRITE，具体语法以选定 Flink 版本为准。
- 初始化语句须位于 INSERT 之前；多个 INSERT 合并为一个 StatementSet 作业。
- 当前 Catalog、数据库、运行镜像、Flink 配置、依赖及 PodTemplate 随草稿保存。选择已登记 Catalog 时自动生成初始化前缀，不必重复手写相同初始化。
- 从数据源插入建表 SQL，或从 JDBC Catalog 浏览库表字段并插入完整表引用；不读取业务行。
- 保存草稿不执行 SQL。“生成部署草稿”与实际执行/部署是不同阶段；发布后在作业管理中启停、检查状态和恢复。
- SQL 保存历史恢复只修改开发草稿；普通作业发布版本恢复不会同步改写 SQL 草稿。被草稿引用的 Catalog 有删除保护。

单脚本上限 32 KiB UTF-8、100 条语句。SQL SET 参数填入 Flink 配置区域；不提供交互式 SELECT 结果、SQL Gateway、在线调试、独立 UDF 管理或云厂商专属 SQL。

校验分三个层次：

| 层次 | 检查内容 |
| --- | --- |
| BASIC | 分句、引号、注释、顺序、长度及允许的语句类型 |
| 精确版本 Parser | 已安装适配器时使用对应 Flink/Calcite 进行语法检查；不加载用户 Connector、不连接 Catalog |
| Flink Runner / Planner | 运行镜像中的真实语义和依赖验证；运行失败通过作业状态反馈 |

Kubernetes dry-run 只校验提交资源，不能替代 SQL Planner。SQL Runner、Parser 与插件的准备方式见 [部署指南](deployment.md#6-sql-runnerparser-与运行镜像)。

## 5. 数据源与 Flink Catalog

“数据管理”集中提供数据源和 Catalog 两个页签。登记只是保存定义，远端测试/库表浏览均由用户显式触发。

| 数据源 | 主要配置 | 平台测试与浏览 |
| --- | --- | --- |
| MySQL | JDBC、字段、主键、精度、密码引用、TLS | JDBC 连接与库表字段/主键 |
| PostgreSQL | JDBC、数据库、Schema、密码引用、TLS | JDBC 连接与登记数据库内指定 Schema 的表字段 |
| Kafka | Topic、消费组、格式、起始位点/时间戳 | Topic 元数据，不消费业务消息 |
| Upsert Kafka | 主键、key/value 格式 | Topic 元数据 |
| 文件系统 | S3/S3A/HDFS/file URI、文件格式 | 配置校验，不在平台读取文件 |
| DataGen / Print | 生成速率、字段或调试输出 | 配置校验，无远端连接 |

JDBC 数据源是有界读取，不代表 CDC。Kafka 当前使用 PLAINTEXT，未支持 SASL/SSL。字段支持精度、非空、主键与事件时间 Watermark；元数据映射后仍应核对业务类型。

| Catalog | 当前能力 | 所需运行环境 |
| --- | --- | --- |
| 内存 | generic_in_memory、默认数据库 | Flink Table 环境 |
| JDBC | MySQL / PostgreSQL 数据源引用、按需库表浏览 | 对应 JDBC Catalog/Connector、数据库驱动 |
| Hive | hive-site.xml 目录配置 | Hive/Hadoop 依赖及配置挂载 |
| Paimon | Filesystem metastore、Warehouse | Paimon 插件及存储配置 |
| Iceberg | Hadoop Catalog、Warehouse | Iceberg 插件及存储配置 |

JDBC Catalog 当前只复用默认 PREFERRED TLS；显式 TLS 模式须使用保留相应参数的数据源建表 SQL，不能静默降级。PostgreSQL 的 schema.table 在该目录中作为一个表标识。平台侧 JDBC 元数据一次最多返回 200 个库/表（超出标记 truncated），字段最多 100 个。

Hive/Paimon/Iceberg 只做初始化配置校验，后端不加载其插件；不提供远端建库、删表或业务数据预览。注册配置变化在下一次预览/发布时生效，不影响已运行作业。

管理员可单独验证一个 host:port 的 TCP 可达性，端口范围 1–65535；连接超时 3 秒。结果区分 TCP、PROTOCOL、CONFIGURATION，配置校验成功不表示网络已连通。网络测试从平台后端发出，不能代表 Flink Pod 的网络或权限。

## 6. 配置、密钥与制品

配置管理支持普通值、AES-256-GCM 加密值、已有 Kubernetes Secret 引用；敏感输入隐藏，已保存密钥不回传明文。2026-09-30：启动时将成对配置的 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 加密导入 s3-access-key / s3-secret-key；仅新增不存在的记录，重启不覆盖管理员维护的值。缺少环境凭据时可在配置管理中手动建立这两个密钥或 Secret 引用。

| 用法 | 写法与约束 |
| --- | --- |
| YAML 普通配置 | 引号包裹的完整标量 "{{config:id}}"，支持类型化替换，不能在字符串中拼接 |
| YAML 密钥 | 容器 env.value 中使用 "{{secret:id}}"，后端转换为 Kubernetes Secret 引用 |
| SQL 密钥 | SQL 单引号字符串内使用 '{{env:DB_PASSWORD}}'，在 PodTemplate 中配置对应环境变量；Runner 在容器中读取并转义 |

例如先登记 db-password 密钥，再设置主容器环境变量：
~~~yaml
spec:
  podTemplate:
    spec:
      containers:
        - name: flink-main-container
          env:
            - name: DB_PASSWORD
              value: "{{secret:db-password}}"
~~~

SQL 连接属性引用为 'password' = '{{env:DB_PASSWORD}}'。优先使用数据源/Catalog 生成的引用，避免手工环境变量冲突。

2026-10-01：文件页采用桶信息、存储位置导航、限高文件表格和所选文件详情侧栏；JAR/SQL 依赖选择器使用内嵌的文件夹/文件列表、统一搜索、面包屑及限高滚动，显示文件大小和选中状态；目录不再弹出遮挡其他字段。文件页为 S3 对象浏览器，提供目录面包屑、筛选、分页、创建目录、上传、单文件下载和所选文件删除，取消制品登记表单。上传直接写入当前目录，使用条件写入拒绝同名覆盖，不生成新的制品登记记录。作业依赖直接从配置前缀下的 jobs/{平台 UUID}/ 或 shared/ 选择，创建前预分配 UUID，保存后保持同一目录。已有登记记录仅供历史 YAML/API 兼容。对象删除不递归，文件为共享资源，删除前需要确认。直接引用对象时仅查询存在性和大小，不下载计算 SHA-256，单文件限制 1 GiB；Java 源文件可存放，不能作为主 JAR 执行。公共 spec.podTemplate 定义一次 initContainer，Operator 将其应用到 JM/TM Pod；下载不再校验 SHA-256；下载失败阻止主容器启动。镜像插件、目录遮蔽与凭据配置见部署指南。

## 7. 用户与权限

| 平台角色 | 能力 |
| --- | --- |
| ADMIN | 用户/组、共享配置资源、纳管、连接测试与全部作业操作 |
| DEVELOPER | 授权范围内的 JAR/SQL 开发、部署、删除、运行操作、制品上传与元数据浏览 |
| OPERATOR | 授权范围内的启停、快照和恢复；不能编辑部署配置或删除平台草稿；允许通过停止流程删除对应 FlinkDeployment |
| VIEWER | 授权范围内只读查看与 Flink UI |

作业范围与平台角色同时生效。新作业归创建者，负责人/管理员可以分配只读组、运行组、编辑组及外部链接；旧数据未分配 ACL 时沿用平台角色范围，应按需补分配。

认证使用 Session + CSRF，密码存储使用 BCrypt；账户禁用或相关修订会使旧会话失效。登录、用户密码设置和配置管理的密钥保存，前端使用 RSA-OAEP-SHA256 + AES-256-GCM 加密字段，后端拒绝这些字段的明文提交。加密挑战绑定会话、用途和目标，有效期 120 秒且只能消费一次；修改时留空仍保留原密码/密钥。普通配置、SQL/YAML 中手写的值不属于自动加密字段，应使用密钥引用。

登录页密码按钮支持显示/隐藏、键盘操作和窄屏布局。HTTP IP:NodePort 可使用字段加密，但页面、公钥和 Session Cookie 仍需要 HTTPS 保护；字段加密不替代 TLS。升级协议与部署要求见部署指南。

共享数据源/配置登记不是多租户隔离。“协作与集成”、CI 令牌和人工兼容性矩阵页面已移除，用户组权限保留。

## 8. Operator 状态与版本边界

系统设置显示配置目标的 Operator Deployment 镜像/版本、就绪副本和 Pod 阶段、容器状态、重启次数。首次打开查询一次，其后 3 秒手动刷新；版本取 Deployment 镜像，不取升级期间旧 Pod 的镜像。

创建时根据 Operator 1.7–1.16 的版本规则过滤可选 Flink 镜像，同时提示支持范围。已弃用但仍接受的版本与新作业筛选范围分开展示；未知标签或查询失败明确提示无法判定，不猜测支持范围。

规则及每个发行版本的官方来源保存在 [operator-compatibility.json](../backend/src/main/resources/operator-compatibility.json)，避免在文档维护第二套数值表。平台镜像登记范围为 Flink 1.16–2.3；筛选取平台范围与 Operator 支持范围的交集。版本过滤不能替代实际 CRD、JDK、连接器、状态升级与存储插件验收。

状态配置依据 [Flink 2.3 配置文档](https://nightlies.apache.org/flink/flink-docs-release-2.3/docs/deployment/config/)，停止阶段依据 [Operator 作业生命周期](https://nightlies.apache.org/flink/flink-kubernetes-operator-docs-release-1.13/docs/custom-resource/job-management/)。从本次停止保存的路径自动启动适用于 Operator 已报告完成的保存点；可访问性和状态兼容仍由实际 Flink 恢复验证。

当前测试使用临时 SQLite、本地协议替身、Parser 和浏览器；真实 MySQL、生产 Kubernetes/Operator、S3/PVC、全版本 Flink UI 与连接器组合仍需部署环境验收。功能说明不构成全版本兼容认证。

2026-10-05 验证范围：110 项后端测试、21 项浏览器交互回归、7 项下载器单元测试、前端类型检查与生产构建、格式和后端分层检查通过。后端测试包含隔离本地 MinIO 的真实上传、整目录复制与删除，确认副本目录匹配新 UUID，删除源目录保留副本及共享文件。浏览器使用 API 替身；实际 Flink 1.20.5 容器验证单下载器准备脚本、非 root 从运行镜像复制插件并启动 JobManager，/overview 返回 200。真实生产 Kubernetes/Operator、HA 恢复、MySQL 与 ARM64 仍未验证。

2026-10-01：生成资源仅保留与 jobManager、taskManager 平级的 spec.podTemplate。历史两份角色模板相同则合并；有差异且无法无损合并时明确报错，请在 YAML 整理，避免丢失自定义配置。S3 依赖在未启用状态存储时也会注入下载器密钥引用；下载清单不再包含 SHA-256，历史校验字段和 AWS_DEFAULT_REGION/AWS_REGION 在重新渲染时清除。HTTP 文件不会自动注入 S3 凭据。

2026-10-01：关键操作增加安全日志，包含作业 ID、操作、请求 ID、停止阶段和异常类型；Kubernetes 请求耗时在 DEBUG 级别记录。意外操作异常保留 UNKNOWN 结果，避免永久停留 SUBMITTING。清理已移除的制品登记页面逻辑和字段定义、重复 PodTemplate 合并实现及不可达停止分支，保留历史制品 API 兼容。

2026-10-02：作业行新增“复制”。有编辑权限的管理员/开发者填写新名称后，同步复制源草稿及专属依赖目录，分配新 UUID、自增编号、所有权与状态目录，完成后进入新作业编辑页，不自动部署。共享依赖仍共享引用；不复制运行状态、历史快照、恢复路径、发布绑定或源 ACL。复制要求源修订未过期且目标名称唯一；文件复制失败回滚新草稿，并尝试清理本次部分复制的目标文件。

2026-10-04：S3/S3P 状态目录仍只生成一个 fw-download initContainer。下载器下载程序文件并准备启动脚本；Flink 主容器通过 kubernetes.entry.path 执行脚本，从当前运行镜像 /opt/flink/opt/flink-s3-fs-presto-*.jar 自动找到唯一插件，复制到独立 emptyDir 的 /opt/flink/plugins/s3-fs-presto/，然后携带原参数执行原入口。无需上传 S3 插件或从镜像标签猜测补丁版本。缺失或多个匹配 JAR 会明确报错并停止启动。主容器插件卷可写，脚本卷只读；插件不会进入 usrlib。旧托管插件容器和下载项自动清理，自定义容器保留，挂载冲突拒绝生成。关闭 S3 状态时移除托管脚本、插件卷并恢复原入口；有程序依赖时继续保留下载器。

2026-10-04：复制作业弹窗改为紧凑居中布局，分区展示源作业、新名称和复制范围，底部统一放置取消与确认按钮；复制中保持禁用并展示进度。

2026-10-04：JAR 运行资源增加 JM/TM Metaspace 数字与单位输入；旧值回填，未显式配置显示 Flink 默认 256 MB，新建无模板作业采用 JM 总内存 2 GB、Metaspace 512 MB。Flink UI 入口识别上游 Metaspace 错误并显示调整及重新部署说明；已有运行作业不自动变更。


2026-10-08：Savepoint 后停止兼容旧版 lastSavepoint 与新版 upgradeSavepointPath。删除前核对本次停止配置的 generation、Operator 观测代数和挂起终态；旧字段须标记 UPGRADE，新字段须匹配已完成的 UPGRADE 类型 FlinkStateSnapshot Savepoint，避免把终态 Checkpoint 当作保存点。恢复路径先持久化，再带 UID/resourceVersion 前置条件删除；删除失败重试仍重新核对，外部恢复运行、修改配置或替换资源时停止自动推进。Operator 报错或快照未确认时保留资源并说明原因，可显式改选立即删除。提交响应丢失后可通过刷新继续；资源提前消失会明确提示未确认保存点。仍仅在手动刷新或再次停止时推进，不增加后台轮询。

2026-10-08 停止流程修复验证：后端共 121 项测试，120 项通过，1 项依赖本地 MinIO 的可选测试跳过；其中新增 11 项停止回归覆盖新旧保存点状态、快照证明、失败重试、配置代数及 UID 变化、响应丢失和提前删除。后端注释/分层检查通过。修复验证未操作实际集群；同日按用户要求发布 app:0.0.4，镜像摘要与启动验证见部署指南。
