package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.artifact.support.ArtifactPodBuilder;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 通过下载器准备启动脚本，由 Flink 主容器从自身镜像安装匹配的 S3 插件。 */
@Component
public class S3PluginPodBuilder {
  private static final String ENTRY = "/flink-bootstrap/start-flink.sh";
  private static final String ORIGINAL = "FLINK_WEB_ORIGINAL_ENTRYPOINT";
  private final PodTemplates templates;
  private final ArtifactPodBuilder artifacts;
  private final ObjectMapper mapper;

  /** 注入共享模板、下载器及 JSON 工具。 */
  public S3PluginPodBuilder(
      PodTemplates templates, ArtifactPodBuilder artifacts, ObjectMapper mapper) {
    this.templates = templates;
    this.artifacts = artifacts;
    this.mapper = mapper;
  }

  /** 迁移历史插件下载配置，并确保公共模板只有一个托管初始化容器。 */
  public void apply(ObjectNode spec) {
    templates.normalize(spec);
    boolean enabled = false;
    for (String key :
        List.of(
            "state.checkpoints.dir",
            "state.savepoints.dir",
            "execution.checkpointing.dir",
            "execution.checkpointing.savepoint-dir",
            "high-availability.storageDir"))
      if (spec.path("flinkConfiguration").path(key).asText().matches("s3(p)?://.+")) enabled = true;
    JsonNode current = spec.path("podTemplate").path("spec");
    ArrayNode manifest = mapper.createArrayNode(), env = mapper.createArrayNode();
    boolean managed = false;
    String original =
        spec.path("flinkConfiguration")
            .path("kubernetes.entry.path")
            .asText("/docker-entrypoint.sh");
    if (ENTRY.equals(original)) original = "/docker-entrypoint.sh";
    for (JsonNode container : current.path("containers"))
      if ("flink-main-container".equals(container.path("name").asText()))
        for (JsonNode variable : container.path("env"))
          if (ORIGINAL.equals(variable.path("name").asText()))
            original = variable.path("value").asText();
    for (JsonNode init : current.path("initContainers")) {
      if ("fw-s3-plugin".equals(init.path("name").asText())) managed = true;
      if (!"fw-download".equals(init.path("name").asText())) continue;
      managed = true;
      if (init.path("env").isArray()) env = ((ArrayNode) init.get("env")).deepCopy();
      for (JsonNode variable : env)
        if ("ARTIFACT_MANIFEST".equals(variable.path("name").asText()))
          try {
            JsonNode parsed = mapper.readTree(variable.path("value").asText());
            if (!(parsed instanceof ArrayNode)) fail("下载清单格式错误");
            manifest = (ArrayNode) parsed;
          } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            fail("下载清单格式错误");
          }
    }
    if (!enabled
        && !managed
        && !ENTRY.equals(spec.path("flinkConfiguration").path("kubernetes.entry.path").asText()))
      return;
    if (current.isObject()) {
      remove((ObjectNode) current, "initContainers", "fw-s3-plugin");
      for (String name : List.of("fw-s3-plugin", "fw-bootstrap")) {
        remove((ObjectNode) current, "volumes", name);
        for (JsonNode container : current.path("containers"))
          if (container.isObject()) remove((ObjectNode) container, "volumeMounts", name);
      }
      for (JsonNode container : current.path("containers"))
        if (container.isObject() && "flink-main-container".equals(container.path("name").asText()))
          remove((ObjectNode) container, "env", ORIGINAL);
    }
    removeEnv(env, "FLINK_PLUGIN_BOOTSTRAP");
    for (int i = manifest.size() - 1; i >= 0; i--) {
      JsonNode entry = manifest.get(i);
      boolean presto = entry.path("filename").asText().matches("flink-s3-fs-presto-.+\\.jar");
      if (enabled
          && presto
          && spec.path("job")
              .path("jarURI")
              .asText()
              .endsWith("/" + entry.path("filename").asText())) fail("S3 文件系统插件不能作为主 JAR");
      if (entry.path("managedPlugin").asBoolean()
          || "s3-plugin".equals(entry.path("target").asText())) manifest.remove(i);
    }
    if (enabled) env.addObject().put("name", "FLINK_PLUGIN_BOOTSTRAP").put("value", "true");
    artifacts.install(spec, manifest, env);
    if (!enabled) {
      if (ENTRY.equals(spec.path("flinkConfiguration").path("kubernetes.entry.path").asText()))
        spec.withObject("/flinkConfiguration").put("kubernetes.entry.path", original);
      return;
    }
    ObjectNode pod = spec.withObject("/podTemplate/spec");
    ObjectNode main = mapper.createObjectNode().put("name", "flink-main-container");
    main.putArray("env").addObject().put("name", ORIGINAL).put("value", original);
    for (String name : List.of("fw-s3-plugin", "fw-bootstrap")) {
      String path =
          name.equals("fw-bootstrap") ? "/flink-bootstrap" : "/opt/flink/plugins/s3-fs-presto";
      for (JsonNode container : pod.path("containers"))
        if ("flink-main-container".equals(container.path("name").asText()))
          for (JsonNode mount : container.path("volumeMounts"))
            if (path.equals(mount.path("mountPath").asText())
                && !name.equals(mount.path("name").asText()))
              fail("podTemplate: 目录已有自定义挂载：" + path);
      ObjectNode volume = mapper.createObjectNode().put("name", name);
      volume.putObject("emptyDir").put("sizeLimit", name.equals("fw-bootstrap") ? "1Mi" : "256Mi");
      templates.upsert(pod, "volumes", volume);
      main.withArray("volumeMounts")
          .addObject()
          .put("name", name)
          .put("mountPath", path)
          .put("readOnly", name.equals("fw-bootstrap"));
    }
    templates.upsert(pod, "containers", main);
    for (JsonNode init : pod.path("initContainers"))
      if ("fw-download".equals(init.path("name").asText()))
        ((ObjectNode) init)
            .withArray("volumeMounts")
            .addObject()
            .put("name", "fw-bootstrap")
            .put("mountPath", "/flink-bootstrap");
    spec.withObject("/flinkConfiguration").put("kubernetes.entry.path", ENTRY);
  }

  /** 移除托管环境变量。 */
  private void removeEnv(ArrayNode list, String name) {
    for (int i = list.size() - 1; i >= 0; i--)
      if (name.equals(list.get(i).path("name").asText())) list.remove(i);
  }

  /** 移除托管元素并保留自定义模板。 */
  private void remove(ObjectNode parent, String field, String name) {
    if (parent.path(field) instanceof ArrayNode list) removeEnv(list, name);
  }

  /** 返回可操作的插件配置错误。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_S3_PLUGIN", message);
  }
}
