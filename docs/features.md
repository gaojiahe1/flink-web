# Flink Web 功能说明

更新：2026-09-29。本文说明当前实现与必须保留的业务规则；安装、环境变量和故障处理见 [部署指南](deployment.md)。

## 1. 平台定位与规则

Flink Web 使用 Spring Boot / JDK 17 和 Vue 3 / TypeScript，通过运维已部署的 Flink Kubernetes Operator 管理固定 namespace 中的 Application 作业。平台维护 FlinkDeployment 声明，Operator 负责实际协调；平台不安装 Operator，不修改其 Webhook。

- **按需查询**：首次进入作业列表查询一次，其后手动刷新，前端冷却 3 秒。多用户、多标签独立请求，没有后台轮询、watch、跨页面状态缓存或请求合并。提交和纳管时会进行必要的即时身份/状态检查。
- **状态语义**：草稿、期望状态和最近观测状态分别展示。操作返回 ACCEPTED 仅表示请求已受理，不代表作业运行成功；最终状态以手动刷新结果为准。
- **停止与删除**：停止仅对运行作业开放，底层为 job.state=suspended。运行、挂起、未知状态禁止删除；资源进入允许的终态且期望状态非 suspended 后才能删除集群资源。集群资源删除完成后才可删除平台草稿。后端再次校验，不能绕过按钮。
- **存储**：默认本地 SQLite，可挂 PVC；可切换 MySQL。切换配置不会迁移已有数据。当前按单后端实例部署。默认自动判断数据库文件：不存在时建库，已存在时保留数据并执行必要版本迁移，无需首次部署补丁或切换开关；启动报告区分本地库缺失与初始管理员密码配置错误，给出修复步骤。S3 不存储平台元数据库。
- **范围**：不管理多集群、多作业 namespace、Session 集群或 FlinkSessionJob。Operator 可以位于另一个已明确配置并授权的 namespace。

## 2. 作业管理

| 能力 | 使用方式与边界 |
| --- | --- |
| 列表与筛选 | 按名称、描述、状态、Flink 版本筛选，查看最近查询时间；支持批量启动、停止和快照操作，逐项展示结果 |
| 既有作业纳管 | 管理员确认后登记已有 Application 资源，保留原 spec，以 UID 绑定；纳管不修改集群、不重启作业。同名冲突、旧 UID、过期 resourceVersion 或正在删除的资源会被拒绝 |
| 当前 YAML | 每次刷新同时返回当前 FlinkDeployment 的只读脱敏快照；点击“当前 YAML”只打开本页结果，不再次请求。刷新失败清空结果 |
| 编辑与发布 | 保存草稿只写元数据；部署时校验 YAML、渲染变量、执行 Kubernetes dry-run，再提交资源，避免字段被 CRD 静默裁剪 |
| 发布版本 | 固定提交内容，按行对比并恢复为新草稿；恢复不自动部署、不回滚运行状态或历史密钥。实际提交失败时仍可能有发布记录 |
| 诊断与日志 | 手动查询 Pod、事件、受限长度容器日志及上一实例日志；验证 Pod 所属关系，不跟随日志、不保存原始日志 |
| 快照与恢复 | 手动触发 savepoint/checkpoint，查看已观测快照，检查 S3 路径或选择指定路径恢复 |
| Flink UI | 从作业行打开同源只读代理，与平台共用 IP:NodePort；受登录、角色与作业权限控制 |
| 审计与操作记录 | 写操作记录审计，运行操作使用 requestId 幂等；UNKNOWN 表示结果不确定，应先查询再决定是否重试 |

运行 YAML 中的密钥、环境变量字面值、命令参数、注解及嵌入协调配置会隐藏，managedFields 不返回；该副本不能直接用于重新部署。日志会遮蔽已知密钥与常见凭据模式，但不能保证识别任意应用输出中的所有敏感内容。

新建作业的 checkpoint 默认存 S3，只保留最近一个已完成点，由 Flink 清理；savepoint 单独保留。纳管不改变既有状态目录与保留策略。快照台账只记录用户刷新时观测到的完成点，不补造遗漏记录；_metadata 存在不等于共享状态完整或升级兼容。last-state 需要运行环境提供 HA。

页面不提供重启按钮；停止与启动为明确的独立操作。删除平台作业不会自动清理 S3 制品、状态文件或历史 Secret。

## 3. JAR、YAML、模板与镜像

从“新建 JAR 作业”进入，内部可切换 JAR 表单与 YAML 编辑。

1. 填写作业名称与描述，选择镜像及可选模板。
2. 选择或上传主 JAR，单独选择附加依赖/配置文件；主包与依赖不能重复，文件名不能冲突。
3. 填写入口类、程序参数、并行度、Slot、JM/TM 内存和 CPU。程序参数每行对应一个 argv，保留参数内空格，不作 shell 拆分。
4. 配置 Flink 参数与 PodTemplate，验证并预览。
5. 保存草稿，或显式保存并部署。失败时查看错误与操作记录，不能把受理状态当成成功。

Flink 配置支持逐行 key:value、常用键提示、中文说明、重复键与格式检查。YAML 编辑器保留注释，选择镜像时同步 spec.image 与 spec.flinkVersion。

模板可以包含完整 FlinkDeployment 和 PodTemplate，包括标签、环境变量、卷及 initContainers。SQL 高级配置可以从模板复制 PodTemplate；复制后独立编辑，不随模板自动更新。

镜像登记包含仓库、tag、Flink 版本、可选 JDK 说明及 imagePullSecret。选择框收起显示 Flink 版本，展开显示 tag 和名称。平台不登记或生成镜像 digest；制品 SHA-256 校验仍保留。

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

配置管理支持普通值、AES-256-GCM 加密值、已有 Kubernetes Secret 引用；敏感输入隐藏，已保存密钥不回传明文。

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

制品支持上传至 S3、分类登记、分页浏览目录、筛选及下载，也可登记 HTTP/HTTPS/S3 地址与 SHA-256。Java 源文件可归档，不能作为作业执行。initContainer 在 JM/TM 分别下载和校验；下载失败阻止主容器启动。镜像插件、目录遮蔽与凭据配置见部署指南。

## 7. 用户与权限

| 平台角色 | 能力 |
| --- | --- |
| ADMIN | 用户/组、共享配置资源、纳管、连接测试与全部作业操作 |
| DEVELOPER | 授权范围内的 JAR/SQL 开发、部署、删除、运行操作、制品上传与元数据浏览 |
| OPERATOR | 授权范围内的启停、快照和恢复；不能编辑部署配置或删除资源 |
| VIEWER | 授权范围内只读查看与 Flink UI |

作业范围与平台角色同时生效。新作业归创建者，负责人/管理员可以分配只读组、运行组、编辑组及外部链接；旧数据未分配 ACL 时沿用平台角色范围，应按需补分配。

认证使用 Session + CSRF，密码存储使用 BCrypt；账户禁用或相关修订会使旧会话失效。登录、用户密码设置和配置管理的密钥保存，前端使用 RSA-OAEP-SHA256 + AES-256-GCM 加密字段，后端拒绝这些字段的明文提交。加密挑战绑定会话、用途和目标，有效期 120 秒且只能消费一次；修改时留空仍保留原密码/密钥。普通配置、SQL/YAML 中手写的值不属于自动加密字段，应使用密钥引用。

登录页密码按钮支持显示/隐藏、键盘操作和窄屏布局。HTTP IP:NodePort 可使用字段加密，但页面、公钥和 Session Cookie 仍需要 HTTPS 保护；字段加密不替代 TLS。升级协议与部署要求见部署指南。

共享数据源/配置登记不是多租户隔离。“协作与集成”、CI 令牌和人工兼容性矩阵页面已移除，用户组权限保留。

## 8. Operator 状态与版本边界

系统设置显示配置目标的 Operator Deployment 镜像/版本、就绪副本和 Pod 阶段、容器状态、重启次数。首次打开查询一次，其后 3 秒手动刷新；版本取 Deployment 镜像，不取升级期间旧 Pod 的镜像。

创建时根据 Operator 1.7–1.16 的版本规则过滤可选 Flink 镜像，同时提示支持范围。已弃用但仍接受的版本与新作业筛选范围分开展示；未知标签或查询失败明确提示无法判定，不猜测支持范围。

规则及每个发行版本的官方来源保存在 [operator-compatibility.json](../backend/src/main/resources/operator-compatibility.json)，避免在文档维护第二套数值表。平台镜像登记范围为 Flink 1.16–2.3；筛选取平台范围与 Operator 支持范围的交集。版本过滤不能替代实际 CRD、JDK、连接器、状态升级与存储插件验收。

当前测试使用临时 SQLite、本地协议替身、Parser 和浏览器；真实 MySQL、生产 Kubernetes/Operator、S3/PVC、全版本 Flink UI 与连接器组合仍需部署环境验收。功能说明不构成全版本兼容认证。
