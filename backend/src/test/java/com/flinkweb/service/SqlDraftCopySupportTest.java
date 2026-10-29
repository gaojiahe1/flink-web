package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.service.job.creation.impl.YamlServiceImpl;
import com.flinkweb.service.job.creation.support.PodTemplates;
import com.flinkweb.service.sql.support.SqlDraftCopySupport;
import org.junit.jupiter.api.Test;

/** 验证 SQL 专属文件迁移及 YAML 重置边界，不连接真实对象存储。 */
class SqlDraftCopySupportTest {
  final ObjectMapper mapper = new ObjectMapper();
  final S3StorageClient storage = mock(S3StorageClient.class);
  final YamlServiceImpl yaml =
      new YamlServiceImpl(mapper, new PlatformProperties("team", null, null), new PodTemplates());
  final SqlDraftCopySupport copies = new SqlDraftCopySupport(storage, yaml);
  final String from = "00000000-0000-4000-8000-000000000001";

  /** 两种 YAML 模式都清理身份且保留扩展字段，公共依赖不搬迁。 */
  @Test
  void rebasesOwnedDependenciesAndBothYamlModesWithoutTouchingSharedFilesOrSource() {
    when(storage.jobPrefix(anyString()))
        .thenAnswer(i -> "artifacts/jobs/" + i.getArgument(0) + "/");
    when(storage.jobStateRoot(anyString())).thenAnswer(i -> "s3://state/" + i.getArgument(0) + "/");
    var resource = mapper.createObjectNode();
    resource
        .putObject("metadata")
        .put("name", "old")
        .put("uid", "old-uid")
        .putObject("labels")
        .put("flink-web/sql-id", "old");
    var spec = resource.putObject("spec").put("restartNonce", 99);
    spec.putObject("job").put("args", "old-args").put("initialSavepointPath", "s3://old/savepoint");
    spec.putObject("flinkConfiguration")
        .put("pipeline.name", "old")
        .put("state.checkpoints.dir", "s3://old/checkpoints");
    spec.put("customField", "preserved");
    resource.putObject("status").put("state", "RUNNING");
    var source = mapper.createObjectNode();
    var runtime =
        source
            .putObject("runtime")
            .put("workspaceId", from)
            .put("baseYaml", yaml.dump(resource))
            .put("deploymentYaml", yaml.dump(resource));
    runtime
        .putArray("dependencyArtifactIds")
        .add("s3:artifacts/jobs/" + from + "/udf.jar")
        .add("s3:artifacts/shared/common.jar");
    var original = source.deepCopy();
    var result = copies.prepare(source, "new-sql");
    String to = result.path("workspaceId").asText();
    assertThat(to).isNotEqualTo(from);
    assertThat(result.toString())
        .doesNotContain("artifacts/jobs/" + from)
        .contains("artifacts/jobs/" + to, "artifacts/shared/common.jar");
    verify(storage).copyJobFiles(from, to, true);
    for (String field : java.util.List.of("baseYaml", "deploymentYaml")) {
      var copied = yaml.parse(result.path(field).asText());
      assertThat(copied.at("/metadata/name").asText()).isEqualTo("new-sql");
      assertThat(copied.at("/spec/customField").asText()).isEqualTo("preserved");
      assertThat(copied.toString())
          .doesNotContain("old-uid", "old-args", "s3://old", "restartNonce", "status");
    }
    assertThat(source).isEqualTo(original);
  }

  /** 部分复制失败仅清理本次创建的目录，保留原异常。 */
  @Test
  void failedFileCopyCleansOnlyNewWorkspaceAndKeepsOriginalFailure() {
    when(storage.jobPrefix(anyString())).thenAnswer(i -> "jobs/" + i.getArgument(0) + "/");
    var failure = new IllegalStateException("copy failed");
    doThrow(failure).when(storage).copyJobFiles(eq(from), anyString(), eq(true));
    var source = mapper.createObjectNode();
    source
        .putObject("runtime")
        .put("workspaceId", from)
        .put("runnerArtifactId", "s3:jobs/" + from + "/runner.jar");
    assertThatThrownBy(() -> copies.prepare(source, "new")).isSameAs(failure);
    var id = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(storage).deleteJobStorage(id.capture(), eq(true));
    assertThat(id.getValue()).isNotEqualTo(from).matches("[a-f0-9-]{36}");
  }
}
