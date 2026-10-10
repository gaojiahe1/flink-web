package com.flinkweb.service.sql.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.repository.metadata.RecordRepository;
import org.springframework.stereotype.Component;

/** 为 SQL 与 Session 复用普通 Flink 镜像，通过统一下载器挂载版本匹配的 Runner。 */
@Component
public class SqlRuntimeArtifacts {
  private final S3StorageClient storage;
  private final RecordRepository records;

  /** 注入默认文件目录及镜像登记。 */
  public SqlRuntimeArtifacts(S3StorageClient storage, RecordRepository records) {
    this.storage = storage;
    this.records = records;
  }

  /** 必须由用户显式选择 Runner，不根据镜像版本推断文件。 */
  public String runner(ObjectNode runtime) {
    String selected = runtime.path("runnerArtifactId").asText();
    if (!selected.isBlank()) return selected;
    throw new com.flinkweb.exception.common.BusinessException(
        org.springframework.http.HttpStatus.BAD_REQUEST,
        "INVALID_RUNNER_VERSION",
        "runnerArtifactId: 请选择 SQL Runner 文件，或确认具体 HTTP/S3 JAR 地址");
  }

  /** 将 Runner 作为主 JAR 挂载，连接器仍保持附加依赖语义。 */
  public void configure(ObjectNode runtime) {
    runtime.remove(java.util.List.of("artifactIds", "jarURI"));
    runtime.put("mainArtifactId", runner(runtime));
    runtime.put("entryClass", "com.flinkweb.sql.SqlRunner");
  }
}
