from pathlib import Path
root = Path(__file__).resolve().parent.parent / 'backend/src/main/java/com/flinkweb'
replacements = {
 'config/permission/SecurityConfiguration.java': [('safe(req.getRequestURI()));', 'safe(req.getRequestURI()), ex);'), ('ex.getClass().getSimpleName());', 'ex.getClass().getSimpleName(), ex);')],
 'exception/common/GlobalExceptionHandler.java': [('ex.code());', 'ex.code(), ex);'), ('ex.getClass().getSimpleName());', 'ex.getClass().getSimpleName(), ex);')],
 'collector/job/FlinkStateCollector.java': [('ex.code());', 'ex.code(), ex);')],
 'service/job/runtime/impl/OperationServiceImpl.java': [('id, action, key, ex.code());', 'id, action, key, ex.code(), ex);')],
 'client/storage/S3StorageClient.java': [('ex.getClass().getSimpleName());', 'ex.getClass().getSimpleName(), ex);'), ('"Partial job copy cleanup failed targetJobId={}", to);', '"Partial job copy cleanup failed targetJobId={}", to, cleanup);'), ('throw unavailable();', 'throw unavailable(ex);'), ('private BusinessException unavailable() {', 'private BusinessException unavailable(Throwable cause) {'), ('"S3 请求失败，请检查端点、凭证、目录权限和对象是否存在");', '"S3 请求失败，请检查端点、凭证、目录权限和对象是否存在", cause);')],
 'service/job/runtime/impl/FlinkUiServiceImpl.java': [('upstream.statusCode());\n          response.setHeader', 'upstream.statusCode(), new IOException("Flink REST returned HTTP " + upstream.statusCode()));\n          response.setHeader'), ('job.deploymentName());\n            response.setHeader', 'job.deploymentName(), new IOException("JobManager returned Metaspace error"));\n            response.setHeader'), ('ex.getClass().getSimpleName());', 'ex.getClass().getSimpleName(), ex);'), ('throw unavailable();', 'throw unavailable(ex);'), ('private BusinessException unavailable() {', 'private BusinessException unavailable(Throwable cause) {'), ('"Flink UI 不可访问，请确认 JobManager 和 REST Service 已就绪");', '"Flink UI 不可访问，请确认 JobManager 和 REST Service 已就绪", cause);'), ('Thread.currentThread().interrupt();\n      throw', 'Thread.currentThread().interrupt();\n      log.warn("Flink UI proxy interrupted jobId={} deployment={} path={}", id, job.deploymentName(), upstreamPath, ex);\n      throw')],
}
for name, edits in replacements.items():
    path = root / name
    content = path.read_text(encoding='utf-8')
    for old,new in edits:
        if old not in content: raise RuntimeError(f'Missing replacement in {name}: {old}')
        content=content.replace(old,new)
    path.write_text(content,encoding='utf-8')
