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
  private final SqlUdfSupport udfs;
  private final com.flinkweb.service.artifact.support.ArtifactReferences references;
  private final com.flinkweb.service.sql.catalog.FlinkCatalogService catalogs;

  /** 注入默认文件目录及镜像登记。 */
  public SqlRuntimeArtifacts(
      S3StorageClient storage,
      RecordRepository records,
      SqlUdfSupport udfs,
      com.flinkweb.service.sql.catalog.FlinkCatalogService catalogs,
      com.flinkweb.service.artifact.support.ArtifactReferences references) {
    this.storage = storage;
    this.records = records;
    this.udfs = udfs;
    this.references = references;
    this.catalogs = catalogs;
  }

  /** 保存前重建 UDF 元信息，防止伪造签名或选中不存在的函数。 */
  public void validateUdfs(ObjectNode runtime) {
    udfs.canonicalize(runtime);
  }

  /** 与部署下载器使用相同地址校验，避免把外部文件地址当作历史登记编号。 */
  public String filename(String reference) {
    return references.describe(reference).path("filename").asText();
  }

  /** 发布与专用 Session 验证使用相同目录环境和依赖，不因只做检查而丢掉配置。 */
  public void dependencies(ObjectNode runtime) {
    catalogs.prepare(runtime);
    udfs.dependencies(runtime);
  }

  /** 优先保留显式选择，否则固定使用默认共享 Runner，不根据镜像版本猜文件。 */
  public String runner(ObjectNode runtime) {
    String selected = runtime.path("runnerArtifactId").asText();
    if (!selected.isBlank()) return selected;
    String prefix =
        storage.defaults().path("rootPrefix").asText("artifacts/").replaceAll("^/+|/+$", "");
    return "s3:"
        + (prefix.isBlank() ? "" : prefix + "/")
        + "shared/sql-runner/flink-web-sql-runner.jar";
  }

  /** 将 Runner 作为主 JAR 挂载，连接器仍保持附加依赖语义。 */
  public void configure(ObjectNode runtime) {
    dependencies(runtime);
    runtime.remove(java.util.List.of("artifactIds", "jarURI"));
    runtime.put("mainArtifactId", runner(runtime));
    runtime.put("entryClass", "com.flinkweb.sql.SqlRunner");
  }
}
