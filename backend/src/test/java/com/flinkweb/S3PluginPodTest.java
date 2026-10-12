package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.artifact.support.ArtifactPodBuilder;
import com.flinkweb.service.job.creation.support.*;
import com.flinkweb.service.system.DownloaderSettingsService;
import org.junit.jupiter.api.Test;

/** 验证单下载器、插件目录隔离、历史迁移和版本约束。 */
class S3PluginPodTest {
  final ObjectMapper mapper = new ObjectMapper();

  /** 构建不连接实际集群或存储的下载器。 */
  S3PluginPodBuilder builder() {
    var storage = mock(S3StorageClient.class);
    when(storage.defaults())
        .thenReturn(
            mapper.createObjectNode().put("bucket", "files").put("rootPrefix", "artifacts/"));
    var settings = mock(DownloaderSettingsService.class);
    when(settings.requireImage()).thenReturn("registry.test/downloader:0.0.4");
    var templates = new PodTemplates();
    var artifacts =
        new ArtifactPodBuilder(
            mapper,
            mock(RecordRepository.class),
            storage,
            settings,
            templates,
            mock(com.flinkweb.service.artifact.support.ArtifactReferences.class),
            "",
            "http://minio:9000",
            "s3-creds");
    return new S3PluginPodBuilder(templates, artifacts, mapper);
  }

  /** 提供有 S3 状态的最小配置。 */
  ObjectNode spec() {
    var spec = mapper.createObjectNode().put("image", "registry.test/flink:1.20.5-scala_2.12");
    spec.putObject("flinkConfiguration").put("state.checkpoints.dir", "s3://state/job/checkpoints");
    spec.putObject("job").put("jarURI", "local:///opt/flink/usrlib/main.jar");
    return spec;
  }

  /** 纯插件下载不遮蔽镜像内 usrlib，重复生成仍只有一个初始化容器。 */
  @Test
  void pluginOnlyUsesOneDownloaderAndKeepsImageJarVisible() throws Exception {
    var spec = spec();
    var builder = builder();
    builder.apply(spec);
    builder.apply(spec);
    var pod = spec.at("/podTemplate/spec");
    assertThat(pod.path("initContainers")).hasSize(1);
    var init = pod.path("initContainers").get(0);
    assertThat(init.path("name").asText()).isEqualTo("fw-download");
    assertThat(init.path("image").asText()).isEqualTo("registry.test/downloader:0.0.4");
    assertThat(init.path("imagePullPolicy").asText()).isEqualTo("Always");
    assertThat(pod.path("containers").get(0).path("volumeMounts")).hasSize(2);
    assertThat(init.toString())
        .contains("FLINK_PLUGIN_BOOTSTRAP")
        .doesNotContain("s3://", "secretKeyRef", "AWS_DEFAULT_REGION", "sha256");
    assertThat(spec.at("/flinkConfiguration/kubernetes.entry.path").asText())
        .isEqualTo("/flink-bootstrap/start-flink.sh");
    spec.put("image", "registry.test/flink@sha256:custom");
    builder.apply(spec);
    assertThat(spec.toString()).doesNotContain("flink-s3-fs-presto-1.20.5.jar");
    spec.remove("flinkConfiguration");
    builder.apply(spec);
    assertThat(spec.at("/podTemplate/spec/initContainers")).isEmpty();
    assertThat(spec.at("/podTemplate/spec/volumes")).isEmpty();
  }

  /** 旧独立插件容器消失，用户显式依赖保留在下载清单与共享文件卷。 */
  @Test
  void legacyPluginAndUserFilesMergeWithoutDuplicatingPlugin() throws Exception {
    var spec = spec();
    var pod = spec.withObject("/podTemplate/spec");
    var list = mapper.createArrayNode();
    list.addObject().put("uri", "s3://files/job/main.jar").put("filename", "main.jar");
    list.addObject()
        .put("uri", "s3://files/shared/flink-s3-fs-presto-1.20.5.jar")
        .put("filename", "flink-s3-fs-presto-1.20.5.jar");
    var inits = pod.putArray("initContainers");
    inits.addObject().put("name", "fw-s3-plugin").put("image", "flink:1.20.5");
    inits
        .addObject()
        .put("name", "fw-download")
        .putArray("env")
        .addObject()
        .put("name", "ARTIFACT_MANIFEST")
        .put("value", list.toString());
    builder().apply(spec);
    assertThat(pod.path("initContainers")).hasSize(1);
    var init = pod.path("initContainers").get(0);
    assertThat(init.path("volumeMounts")).hasSize(2);
    String raw = "";
    for (var variable : init.path("env"))
      if ("ARTIFACT_MANIFEST".equals(variable.path("name").asText()))
        raw = variable.path("value").asText();
    var manifest = mapper.readTree(raw);
    assertThat(manifest).hasSize(2);
    assertThat(manifest.get(0).path("filename").asText()).isEqualTo("main.jar");
    assertThat(manifest.get(1).path("filename").asText())
        .isEqualTo("flink-s3-fs-presto-1.20.5.jar");
    assertThat(pod.path("volumes").toString()).contains("fw-artifacts");
    assertThat(pod.path("containers").toString()).contains("/opt/flink/usrlib", "fw-artifacts");
    builder().apply(spec);
    assertThat(pod.path("initContainers")).hasSize(1);
    assertThat(pod.path("containers").toString()).contains("/opt/flink/usrlib", "fw-artifacts");
    assertThat(spec.at("/job/jarURI").asText()).endsWith("/main.jar");
  }

  /** 自定义挂载冲突、无法确定补丁版本时拒绝生成误导性配置。 */
  @Test
  void rejectsUnsafePluginSelection() {
    var spec = spec();
    spec.withObject("/podTemplate/spec")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container")
        .putArray("volumeMounts")
        .addObject()
        .put("name", "custom")
        .put("mountPath", "/opt/flink/plugins/s3-fs-presto");
    assertThatThrownBy(() -> builder().apply(spec)).hasMessageContaining("已有自定义挂载");
    spec.remove("podTemplate");
    spec.put("image", "flink:1.20");
    assertThatCode(() -> builder().apply(spec)).doesNotThrowAnyException();
  }

  /** 角色模板与下载器共存时按名称合并，保留边车并阻止角色覆盖插件目录。 */
  @Test
  void roleTemplatesKeepSidecarsAndRejectManagedMountConflicts() {
    var spec = spec();
    spec.withObject("/jobManager/podTemplate/spec")
        .putArray("containers")
        .addObject()
        .put("name", "sidecar")
        .put("image", "busybox");
    builder().apply(spec);
    assertThat(spec.at("/jobManager/podTemplate/spec/containers/0/name").asText())
        .isEqualTo("sidecar");
    assertThat(
            spec.at("/flinkConfiguration/kubernetes.operator.pod-template.merge-arrays-by-name")
                .asText())
        .isEqualTo("true");
    spec.withObject("/taskManager/podTemplate/spec")
        .putArray("containers")
        .addObject()
        .put("name", "flink-main-container")
        .putArray("volumeMounts")
        .addObject()
        .put("name", "custom")
        .put("mountPath", "/opt/flink/plugins/s3-fs-presto");
    assertThatThrownBy(() -> builder().apply(spec)).hasMessageContaining("已有自定义挂载");
  }

  /** 自定义入口在重复生成及禁用插件后仍可恢复。 */
  @Test
  void preservesCustomEntrypoint() {
    var spec = spec();
    spec.withObject("/flinkConfiguration").put("kubernetes.entry.path", "/custom-entry.sh");
    var builder = builder();
    builder.apply(spec);
    builder.apply(spec);
    assertThat(spec.toString()).contains("/custom-entry.sh");
    spec.withObject("/flinkConfiguration").remove("state.checkpoints.dir");
    builder.apply(spec);
    assertThat(spec.at("/flinkConfiguration/kubernetes.entry.path").asText())
        .isEqualTo("/custom-entry.sh");
    assertThat(spec.toString()).doesNotContain("FLINK_WEB_ORIGINAL_ENTRYPOINT", "fw-bootstrap");
  }
}
