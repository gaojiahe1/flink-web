# 回归与镜像验收

更新：2026-11-04。先运行默认回归，再按修改范围补充联调；测试通过不代表现场 Operator、全部 Flink 版本或 MySQL 已验收。

## 默认回归

需要 Java 17、Maven、项目支持的 Node.js 和 Python 3。使用仓库锁定的依赖；后端仍使用 BES，不能为了测试切换为 Tomcat。

```shell
mvn -f backend/pom.xml test
mvn -f sql-runner/pom.xml test
python scripts/check_backend.py
python scripts/check_agent_docs.py
```

Windows PowerShell 若拦截 npm.ps1，使用 npm.cmd/npx.cmd。受限环境的系统临时目录不可写时，为当前任务建立独占临时目录，通过 Surefire 的 `-DargLine=-Djava.io.tmpdir=绝对路径` 传给测试 JVM；仅设置 Maven 进程的 java.io.tmpdir 不够。

```shell
cd frontend
npm ci
npm run build
npm run format:check
npx playwright install chromium
npm run test:e2e
```

`test:e2e` 只运行 Playwright fixture 项目，API 使用用例声明的替身，无需真实账号或后端。未设置 E2E_BASE_URL 时自动启动 127.0.0.1:5173，结束关闭自建服务；本地已有页面服务可复用，CI 禁止复用并拒绝 test.only。已有 Chrome 可用 E2E_BROWSER 指定完整可执行路径。前端文本固定 LF，避免 Windows 检出换行符导致格式检查失真。

## 真实接口联调

`npm run test:e2e:live` 单独运行 live 项目，会创建、修改测试记录，只能连接一次性的隔离后端、数据库和存储。不要将生产账号或生产地址用于这些用例。

配置进程环境变量 E2E_BASE_URL、E2E_USERNAME、E2E_PASSWORD；账号需要测试管理员权限。采用 scripts/mock_kubernetes.py 时还需 E2E_K8S_FIXTURE，后端必须指向该替身并提供它所需的测试凭证文件。该脚本默认监听回环地址；容器化测试应在独占网络中运行适配副本，不暴露为公共 Kubernetes 服务。

后端联调还需要可用的独占 S3 桶、原加密主密钥配置方式，以及已上传的默认 Runner 对象 `artifacts/shared/sql-runner/flink-web-sql-runner.jar`。所有测试凭据只进入进程环境或独占临时文件，不写入仓库。退出时删除测试数据库、桶所在临时服务和测试容器，保留用户现有服务。

live 项目覆盖真实加密登录/CSRF、配置和用户、Catalog 登记/引用保护、SQL 保存/Parser/生成部署草稿、JAR 编辑、纳管与手动刷新等。远程元数据和部分集群操作仍使用协议替身，不能宣称外部数据库或实际 Flink 作业运行通过。已移除的数据源旧导航改为检查 Catalog 工作台和保留 API；断言按当前业务契约维护，不恢复过时界面。

## 可选外部条件

- 后端真实 Parser：`-Dflink.test.validator=1.20.3产物绝对路径`、`-Dflink.test.validator.1205=1.20.5产物绝对路径`；未提供时明确跳过对应条件测试。
- Parser JAR：scripts/check_sql_jars.py 可对两份 JAR 分别使用 Java 8/17 执行协议用例，并核对 Java 8 字节码；发布时使用与镜像一致的原版产物。
- S3DirectoryLiveTest：只在 FLINK_TEST_MINIO 指定的独占本地 MinIO 中运行，其测试账号由用例固定；禁止借用用户的 Langfuse MinIO。
- Langfuse：通过 scripts/check_ai_observability.py 显式验证直连和 Collector，配置与元数据边界见[接入说明](../deploy/observability/README.md)。默认回归不向真实 Langfuse 发送数据。
- 下载器：在其 Linux 镜像内、只读根和挂载的测试源码中执行 `python -m unittest discover -v`，验证实际 Python/boto3 运行环境。

## 最终镜像与仓库

app/web 必须用正式 Dockerfile 从干净 Kylin 底座构建。离线制品 context 必须来自已验证的当前 JAR/dist 和原版 Parser/Runner/Nginx；不能继承旧业务镜像再覆盖文件。下载器没有变化时可复用经验证的同一制品并记录共享摘要。

最终镜像另行验证非 root、只读根、临时 SQLite、BES/健康检查、真实登录、模型协议/MCP，以及 Web 代理/DNS 恢复。代理回归入口：scripts/check_web_proxy.py。镜像推送后从指定仓库读取内容，与本地被测试镜像逐层比较：

```shell
python scripts/check_registry_images.py --registry http://192.168.68.1:5000 --image 192.168.68.1:5000/flink-web/app:0.0.10 --image 192.168.68.1:5000/flink-web/web:0.0.10 --image 192.168.68.1:5000/flink-web/downloader:0.0.10
```

该只读工具核对 manifest 正文/响应摘要、config、层顺序、全部 gzip 层的大小/SHA-256及解压 diff_id；不推送、不删除仓库内容，仅支持当前单平台镜像及无需认证的指定内网仓库。多个镜像共享层在同次运行内复用已经核验的结果。

发布摘要以[部署指南](deployment.md)为准，测试证据与已验证边界见[验收记录](agent/validation.md)。不要把构建成功、API 受理或标签存在当成运行和内容验收；实际更新 Kubernetes 需要对应授权。
