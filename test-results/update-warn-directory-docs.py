from pathlib import Path
root = Path(__file__).resolve().parent.parent
path = root / 'docs/features.md'
content = path.read_text(encoding='utf-8')
content = content.replace('单文件下载和所选文件删除', '单文件下载和所选目录/文件删除')
content = content.replace('对象删除不递归，文件为共享资源，删除前需要确认。', '2026-10-10：单文件删除仍只删除该对象；目录删除递归清理所选目录标记、全部子目录和文件。管理员/开发者可勾选目录，确认框明确显示递归范围及作业依赖风险。禁止删除桶根、穿越路径及无末尾斜杠的前缀，按精确前缀每批最多 1000 个当前对象清理，保留相邻同名前缀目录；单项失败不能显示成功，刷新结果后可重试。版本化桶的历史版本仍由生命周期策略管理。')
anchor = '后端关键授权、Kubernetes 请求与日志读取、数据库错误记录操作、资源路径、HTTP 状态或错误码及必要身份；不记录请求正文、查询参数、安全令牌或上游错误正文。'
content = content.replace(anchor, anchor + '2026-10-10：后端所有涉及异常的 WARN 传入异常对象，打印完整栈帧、原因链及 suppressed 异常；S3/Kubernetes/代理的业务转换保留原始 cause。CSRF 异常消息遮蔽实际令牌，保留类型与原始栈帧。仅收到上游 HTTP 错误时记录状态和本地检测调用栈，无法凭 HTTP 响应取得远端服务的 Java 堆栈。')
path.write_text(content, encoding='utf-8')
path = root / 'docs/deployment.md'
content = path.read_text(encoding='utf-8')
content = content.replace('对象浏览器限定在 FLINK_WEB_S3_PREFIX 下，依赖默认位于 jobs/{作业 UUID}/，可选择 shared/。', '对象浏览器从制品桶根目录浏览，依赖快捷入口仍位于 FLINK_WEB_S3_PREFIX 下的 jobs/{作业 UUID}/ 和 shared/。')
content = content.replace('可创建目录标记、浏览、下载和删除具体文件，不做递归清理。', '可创建目录标记、浏览、下载、删除具体文件，以及确认后递归删除选中的非根目录。目录删除包含标记、全部子目录和文件，仅作用于带末尾斜杠的精确前缀，禁止桶根、穿越路径和无分隔符前缀；失败可能已经删除部分对象，刷新后重试。')
content = content.replace('删除文件需要 DeleteObject 权限', '删除文件或目录需要对应前缀的 DeleteObject 权限，递归目录还需 ListBucket 权限')
content = content.replace('健康检查为 /actuator/health。', '2026-10-10 排障日志：所有涉及异常的 WARN 打印完整后端调用栈、cause 原因链及 suppressed 异常，包含授权、API 处理、Kubernetes、快照查询、运行操作、S3 与 Flink UI 代理路径。S3 批量删除的部分失败记录错误码、失败数量、目录及已确认删除数量。CSRF 令牌从异常消息中遮蔽；请求正文、安全令牌及 Kubernetes 错误正文仍不额外记录。上游只有 HTTP 错误响应时，日志提供状态及本地检测堆栈，远端根因仍需查看对应服务日志。\n\n健康检查为 /actuator/health。')
path.write_text(content, encoding='utf-8')
