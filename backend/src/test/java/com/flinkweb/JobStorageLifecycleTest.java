package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.dto.job.creation.*;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.job.creation.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** 验证目录清理失败可重试及复制草稿的身份、目录隔离。 */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class JobStorageLifecycleTest {
  static final Path DATABASE;

  static {
    try {
      DATABASE = Files.createTempDirectory("job-storage-").resolve("test.db");
    } catch (Exception ex) {
      throw new ExceptionInInitializerError(ex);
    }
  }

  /** 配置独立测试数据库，不连接实际对象存储。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.storage.local-path", () -> DATABASE.toString());
    registry.add("platform.security.password", () -> "Test-password-12345");
    registry.add(
        "platform.security.master-key", () -> "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  }

  @Autowired JobService jobs;
  @Autowired YamlService yaml;
  @Autowired ObjectMapper mapper;
  @Autowired com.flinkweb.repository.metadata.RecordRepository records;
  @MockitoSpyBean S3StorageClient storage;

  /** 仅替换外部目录写操作，保留真实目录计算规则。 */
  @BeforeEach
  void setup() {
    doNothing().when(storage).deleteJobStorage(anyString(), anyBoolean());
    doNothing().when(storage).copyJobFiles(anyString(), anyString(), anyBoolean());
    doReturn(mapper.createObjectNode().put("bucket", "test-bucket")).when(storage).defaults();
  }

  /** 删除关联部署先保留仍在开发的 SQL 依赖，再清理原作业目录。 */
  @Test
  void sqlDependenciesMoveToNewWorkspaceBeforeDeletingDeployment() {
    var source = source();
    String sqlId = "sql-" + UUID.randomUUID();
    var draft = mapper.createObjectNode().put("jobId", source.id()).put("publishedAt", "previous");
    draft
        .putObject("runtime")
        .put("workspaceId", source.id())
        .putArray("dependencyArtifactIds")
        .add("s3:" + storage.jobPrefix(source.id()) + "connector.jar")
        .add("s3:artifacts/shared/common.jar");
    records.save("sql_jobs", sqlId, draft, 0);
    jobs.delete(source.id(), source.revision());
    var saved = records.require("sql_jobs", sqlId);
    String workspace = saved.at("/runtime/workspaceId").asText();
    assertThat(workspace).isNotEqualTo(source.id());
    assertThat(saved.at("/runtime/dependencyArtifactIds/0").asText())
        .isEqualTo("s3:" + storage.jobPrefix(workspace) + "connector.jar");
    assertThat(saved.at("/runtime/dependencyArtifactIds/1").asText())
        .isEqualTo("s3:artifacts/shared/common.jar");
    var order = inOrder(storage);
    order.verify(storage).copyJobFiles(source.id(), workspace, true);
    order.verify(storage).deleteJobStorage(source.id(), true);
    assertThat(saved.has("jobId")).isFalse();
  }

  /** 生成含主包、共享依赖、恢复路径及状态目录的源草稿。 */
  com.flinkweb.entity.job.JobDraft source() {
    String id = UUID.randomUUID().toString();
    var node =
        mapper
            .createObjectNode()
            .put("apiVersion", "flink.apache.org/v1beta1")
            .put("kind", "FlinkDeployment");
    node.putObject("metadata")
        .put("name", "source")
        .putObject("annotations")
        .put("flink-web/release-id", "old-release");
    var spec =
        node.putObject("spec")
            .put("image", "flink:1.20.5")
            .put("flinkVersion", "v1_20")
            .put("restartNonce", 123);
    spec.putObject("job")
        .put("jarURI", "local:///opt/flink/usrlib/main.jar")
        .put("initialSavepointPath", "s3://flink-state/old/savepoint")
        .put("parallelism", 3)
        .put("savepointTriggerNonce", 55);
    spec.putObject("flinkConfiguration")
        .put("state.checkpoints.dir", "s3://flink-state/" + id + "/checkpoints")
        .put("high-availability.storageDir", "s3://flink-state/" + id + "/ha");
    var manifest = mapper.createArrayNode();
    manifest
        .addObject()
        .put("uri", "s3://test-bucket/artifacts/jobs/" + id + "/main.jar")
        .put("filename", "main.jar");
    manifest
        .addObject()
        .put("uri", "s3://test-bucket/artifacts/shared/common.jar")
        .put("filename", "common.jar");
    spec.withObject("/podTemplate/spec")
        .putArray("initContainers")
        .addObject()
        .put("name", "fw-download")
        .putArray("env")
        .addObject()
        .put("name", "ARTIFACT_MANIFEST")
        .put("value", manifest.toString());
    return jobs.create(
        new DraftRequest(
            "source-" + id.substring(0, 8), "source", "1.20.5", yaml.dump(node), 0, id));
  }

  /** 清理错误必须回滚元数据删除，旧修订号不能触发对象删除。 */
  @Test
  void deleteFailureKeepsDraftAndRetrySucceeds() {
    var source = source();
    assertThatThrownBy(() -> jobs.delete(source.id(), 99)).isInstanceOf(BusinessException.class);
    verify(storage, never()).deleteJobStorage(anyString(), anyBoolean());
    doThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "S3_ERROR", "retry"))
        .when(storage)
        .deleteJobStorage(source.id(), true);
    assertThatThrownBy(() -> jobs.delete(source.id(), source.revision()))
        .isInstanceOf(BusinessException.class);
    assertThat(jobs.get(source.id())).isEqualTo(source);
    doNothing().when(storage).deleteJobStorage(source.id(), true);
    jobs.delete(source.id(), source.revision());
    assertThatThrownBy(() -> jobs.get(source.id())).isInstanceOf(BusinessException.class);
  }

  /** 复制生成新编号及独立目录，不继承运行恢复标记或发布绑定。 */
  @Test
  void copyCreatesIndependentDraftAndRetainsSharedReferences() {
    var source = source();
    var target =
        jobs.copy(source.id(), new CopyJobRequest(source.name() + "-copy", source.revision()));
    assertThat(target.id()).isNotEqualTo(source.id());
    assertThat(target.jobNumber()).isGreaterThan(source.jobNumber());
    var output = yaml.parse(target.yaml());
    assertThat(output.at("/metadata/name").asText()).isEqualTo(target.deploymentName());
    assertThat(output.toString())
        .contains(
            "artifacts/jobs/" + target.id() + "/main.jar",
            "artifacts/shared/common.jar",
            target.id() + "/checkpoints",
            target.id() + "/ha");
    assertThat(output.toString())
        .doesNotContain(
            source.id(),
            "initialSavepointPath",
            "restartNonce",
            "savepointTriggerNonce",
            "old-release");
    verify(storage).copyJobFiles(source.id(), target.id(), true);
    assertThat(jobs.get(source.id())).isEqualTo(source);
  }

  /** 文件复制失败不能留下可误部署的新草稿，重名和旧修订也不触发文件复制。 */
  @Test
  void copyFailureRollsBackDraftAndGuardsSourceRevision() {
    var source = source();
    String name = source.name() + "-copy";
    assertThatThrownBy(() -> jobs.copy(source.id(), new CopyJobRequest(name, 9)))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> jobs.copy(source.id(), new CopyJobRequest(source.name(), 0)))
        .isInstanceOf(BusinessException.class);
    verify(storage, never()).copyJobFiles(anyString(), anyString(), anyBoolean());
    doThrow(new BusinessException(HttpStatus.BAD_GATEWAY, "S3_ERROR", "retry"))
        .when(storage)
        .copyJobFiles(anyString(), anyString(), anyBoolean());
    assertThatThrownBy(() -> jobs.copy(source.id(), new CopyJobRequest(name, 0)))
        .isInstanceOf(BusinessException.class);
    assertThat(jobs.nameAvailable(name, "")).isTrue();
    assertThat(jobs.get(source.id())).isEqualTo(source);
  }

  /** 显式确认允许旧草稿转换，并且复制仍分配目标目录。 */
  @Test
  void explicitConfirmationAllowsLegacyFormEditing() {
    var source = source();
    var updated =
        jobs.update(
            source.id(),
            new DraftRequest(
                source.name(),
                source.description(),
                source.flinkVersion(),
                source.yaml(),
                source.revision(),
                null,
                true));
    assertThat(
            com.flinkweb.service.job.creation.support.JobEditorPolicy.allowsForm(
                yaml.parse(updated.yaml())))
        .isTrue();
    var copied =
        jobs.copy(
            source.id(), new CopyJobRequest(source.name() + "-form", updated.revision(), true));
    assertThat(
            com.flinkweb.service.job.creation.support.JobEditorPolicy.allowsForm(
                yaml.parse(copied.yaml())))
        .isTrue();
    verify(storage).copyJobFiles(source.id(), copied.id(), true);
  }
}
