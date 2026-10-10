from pathlib import Path
import json, re
root = Path(__file__).resolve().parent.parent
digests = json.loads((root/'test-results/warn-directory-image-digests.json').read_text(encoding='utf-8'))
path = root/'docs/deployment.md'
content = path.read_text(encoding='utf-8')
for name, data in digests.items():
    content = re.sub(r'(\| 192\.168\.68\.1:5000/flink-web/' + name + r' \| )sha256:[a-f0-9]+', lambda m: m[1]+data['digest'], content)
content = content.replace('downloader@sha256:7140951cc61de1dd2951155940d91f8d099e398b65b52c5ba37e516eb070a687', 'downloader@'+digests['downloader']['digest'])
content = content.replace('S3 批量删除的部分失败记录错误码、失败数量、目录及已确认删除数量。', 'S3 批量删除的部分失败记录错误码、失败数量、目录及已完成批次的删除数量；S3 日志副本保留原异常每层类型说明、全部栈帧、cause 与 suppressed 关系，SDK 上游正文和签名凭据继续遮蔽。')
content = content.replace('2026-10-01：S3 文件下载按需生成 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY 的 secretKeyRef，不需要 AWS_DEFAULT_REGION。即使作业关闭状态存储，S3 制品仍需要下载凭据；纯 HTTP 下载不添加 S3 变量。配置管理密钥会物化为同 namespace 的不可变 Secret；引用已有 Secret 时不创建副本。', '2026-10-10：S3 文件下载器按需生成 AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY；配置管理的加密值实际提交时展开为环境变量，显式已有 Secret 引用仍保留。下载器注入配置的 AWS_REGION 用于 V4 签名，不生成 AWS_DEFAULT_REGION，也不在 Flink 配置中补 s3.region。即使作业关闭状态存储，S3 制品仍需要下载凭据；纯 HTTP 下载不添加 S3 变量。')
content = content.replace('所有 S3 程序下载仍需工作负载 Secret 凭据；', '所有 S3 程序下载仍需工作负载凭据；')
provider = '''
### 更换 S3 兼容服务（2026-10-10）

S3 服务可以替换为提供兼容接口的云存储或私有存储，平台不以 MinIO 的服务名识别提供商。设置 FLINK_WEB_S3_ENDPOINT、FLINK_WEB_S3_BUCKET、AWS_REGION 和对应 AK/SK；使用临时凭据时还需 AWS_SESSION_TOKEN。Compose 已传递 AWS_REGION，Kubernetes 清单中的当前 MinIO 端点只是部署示例，更换时应明确设置区域端点和凭据来源。

华为 OBS 示例：FLINK_WEB_S3_ENDPOINT=https://obs.cn-north-4.myhuaweicloud.com，AWS_REGION=cn-north-4，桶填写该区域已创建的桶。端点填写区域服务域名，不在端点前附加桶名，不使用控制台/静态网站地址。参数依据 [OBS V4 签名说明](https://support.huaweicloud.com/obs_faq/obs_faq_0274.html)。

后端使用 V4、路径式访问、禁用分块编码、仅在要求时计算/验证 SDK checksum，并启用 LegacyMd5Plugin 为批量删除等必要操作提供 Content-MD5；这是 [AWS SDK 官方的第三方兼容配置](https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/s3-checksums.html)。下载器显式使用 s3v4、配置的签名区域以及 WHEN_REQUIRED checksum 策略，保持 TLS 校验。OBS 的 [批量删除接口](https://support.huaweicloud.com/api-obs/obs_04_0086.html) 要求 Content-MD5/Content-Length，最多 1000 项，quiet 模式仍需检查单项错误。

接入端点需支持平台所用的 ListObjectsV2（含 delimiter/分页）、HeadObject、GetObject、PutObject、CopyObject、DeleteObject、DeleteObjects，以及上传的 If-None-Match 条件写入；若不支持条件写入，不能保证不覆盖同名对象，平台不静默降级。不同区域、存储版本、IAM 授权与网关行为仍需用目标环境验收。现已验证真实隔离 MinIO、HTTP 请求的 XML/MD5 匹配和 cn-north-4 签名区域下载，尚未对华为云 OBS 或其他厂商实测认证。

平台制品、initContainer 下载器和 Flink 状态存储仍是三条连接路径；Flink 运行镜像中的 S3 插件与目标存储也必须兼容。更换存储不会自动迁移已有制品、保存点、数据库或正在运行的作业。

'''
content = content.replace('## 8. 排障与验收', provider+'## 8. 排障与验收')
start = content.index('2026-10-10 镜像发布完成：')
content = content[:start] + '''2026-10-10 镜像发布完成：完整 WARN 异常链、制品目录删除及第三方 S3 checksum/签名区域兼容改动已构建、验证并覆盖推送 app/web/downloader 的 0.0.4 标签，不更新 latest。摘要见上表，已核对仓库 manifest 正文 SHA-256、Docker-Content-Digest 和本地 config 镜像 ID。后端使用通过校验的离线产物及原 Dockerfile 相同 Kylin 运行层；前端按原 Dockerfile 构建；下载器复用已验证的原 Kylin/Python 3.12.15/boto3 1.42.70 运行镜像，仅叠加新的下载脚本，依赖版本不变。三份镜像均为 linux/amd64，app/web UID 10001、下载器 UID 9999。

验证结果：后端 163 项全部通过，含实际精确版本 SQL Parser、隔离 MinIO 上传/复制/整目录删除、批量删除 XML/Content-MD5 匹配、目录边界与幂等、完整异常栈/原因/suppressed 及凭据遮蔽。前端类型检查、生产构建、全源格式和 14 项受影响浏览器回归通过。实际 app/web 镜像通过只读根文件系统启动、临时 SQLite 健康检查、加密登录、完整后端异常栈输出和 14 项页面回归；代理/DNS 恢复检查通过。下载器实际镜像通过 8 项测试及 cn-north-4 区域 S3v4 真实下载。Java 分层、172 个类及 842 个方法/构造器的中文注释和补丁检查通过。

临时容器、网络、测试数据库和打包副本已清理，构建缓存已回收，保留已标记镜像及校验记录。未更新实际 Kubernetes 工作负载，未在华为 OBS 或现场 Kubernetes/Flink 作业中验证本次部署效果；已有 Pod 按授权流程重新拉取镜像后生效。
'''
path.write_text(content, encoding='utf-8')
path = root/'docs/features.md'
content = path.read_text(encoding='utf-8')
content += '\n2026-10-10 S3 提供商约束：用户后续将接入华为等其他 S3 兼容服务。平台通过可配置端点、桶、区域和凭据连接，不限定 MinIO；后端使用必要 checksum 与 Legacy MD5，下载器显式 V4 并注入 AWS_REGION 签名区域。当前已验证隔离 MinIO、MD5 请求和 cn-north-4 区域下载；华为等厂商尚未实测，不将协议替身或 MinIO 结果视为厂商认证。目标服务所需接口、条件写入和三条连接路径见部署指南。\n'
path.write_text(content, encoding='utf-8')
