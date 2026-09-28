package com.flinkweb.service.artifact.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.repository.metadata.RecordRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 按模板合并规则构建 JM/TM 的下载初始化容器、共享卷及凭据引用。 */
@Component
public class ArtifactPodBuilder {
  private final ObjectMapper mapper;
  private final RecordRepository records;
  private final String image, hosts, endpoint, region, credentialsSecret;

  /** 注入制品仓库、JSON 映射器和下载器运行配置。 */
  public ArtifactPodBuilder(
      ObjectMapper mapper,
      RecordRepository records,
      @Value("${platform.artifacts.downloader-image:flink-web-downloader:0.1.0}") String image,
      @Value("${platform.artifacts.allowed-hosts:}") String hosts,
      @Value("${platform.s3.endpoint:}") String endpoint,
      @Value("${platform.s3.region:us-east-1}") String region,
      @Value("${platform.artifacts.credentials-secret:}") String credentialsSecret) {
    this.mapper = mapper;
    this.records = records;
    this.image = image;
    this.hosts = hosts;
    this.endpoint = endpoint;
    this.region = region;
    this.credentialsSecret = credentialsSecret;
  }

  /** 向 JM/TM PodTemplate 注入下载清单、初始化容器和共享卷，并设置主 JAR 地址。 */
  public void apply(ObjectNode spec, JsonNode ids) {
    ArrayNode manifest = mapper.createArrayNode(), env = mapper.createArrayNode();
    Set<String> filenames = new HashSet<>();
    int index = 0;
    for (JsonNode id : ids) {
      var artifact = records.require("artifacts", id.asText());
      String filename = artifact.path("filename").asText();
      if (!filenames.add(filename))
        throw new com.flinkweb.exception.common.BusinessException(
            org.springframework.http.HttpStatus.BAD_REQUEST,
            "DUPLICATE_FILENAME",
            "附加文件名重复：" + filename);
      var entry =
          manifest
              .addObject()
              .put("uri", artifact.path("uri").asText())
              .put("filename", filename)
              .put("sha256", artifact.path("sha256").asText());
      if (!artifact.path("credentialRef").asText("").isEmpty()) {
        String variable = "ARTIFACT_TOKEN_" + index++;
        entry.put("tokenEnv", variable);
        env.addObject()
            .put("name", variable)
            .put("value", "{{secret:" + artifact.path("credentialRef").asText() + "}}");
      }
    }
    env.addObject().put("name", "ARTIFACT_MANIFEST").put("value", manifest.toString());
    env.addObject().put("name", "ARTIFACT_ALLOWED_HOSTS").put("value", hosts);
    env.addObject().put("name", "S3_ENDPOINT").put("value", endpoint);
    env.addObject().put("name", "AWS_DEFAULT_REGION").put("value", region);
    if (!credentialsSecret.isBlank())
      for (String key : List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")) {
        env.addObject()
            .put("name", key)
            .putObject("valueFrom")
            .putObject("secretKeyRef")
            .put("name", credentialsSecret)
            .put("key", key);
      }
    ObjectNode common =
        spec.path("podTemplate").isObject()
            ? ((ObjectNode) spec.get("podTemplate")).deepCopy()
            : mapper.createObjectNode();
    for (String component : List.of("jobManager", "taskManager")) {
      ObjectNode manager = spec.withObject("/" + component);
      ObjectNode pod = merge(common.deepCopy(), manager.path("podTemplate"));
      ObjectNode podSpec = pod.withObject("/spec");
      ObjectNode volume = mapper.createObjectNode().put("name", "fw-artifacts");
      volume.putObject("emptyDir").put("sizeLimit", "2Gi");
      upsert(podSpec, "volumes", volume);
      ObjectNode init = mapper.createObjectNode().put("name", "fw-download").put("image", image);
      init.set("env", env.deepCopy());
      init.putArray("volumeMounts")
          .addObject()
          .put("name", "fw-artifacts")
          .put("mountPath", "/artifacts");
      init.putObject("securityContext")
          .put("allowPrivilegeEscalation", false)
          .put("runAsNonRoot", true)
          .put("runAsUser", 9999)
          .put("readOnlyRootFilesystem", true);
      init.withObject("/resources").withObject("/limits").put("memory", "256Mi").put("cpu", "500m");
      upsert(podSpec, "initContainers", init);
      ArrayNode containers =
          podSpec.path("containers").isArray()
              ? (ArrayNode) podSpec.get("containers")
              : podSpec.putArray("containers");
      ObjectNode main = null;
      for (JsonNode item : containers)
        if (item.path("name").asText().equals("flink-main-container")) main = (ObjectNode) item;
      if (main == null) main = containers.addObject().put("name", "flink-main-container");
      upsert(
          main,
          "volumeMounts",
          mapper
              .createObjectNode()
              .put("name", "fw-artifacts")
              .put("mountPath", "/opt/flink/usrlib")
              .put("readOnly", true));
      podSpec.withObject("/securityContext").put("fsGroup", 9999);
      manager.set("podTemplate", pod);
    }
    spec.remove("podTemplate");
    spec.withObject("/job")
        .put("jarURI", "local:///opt/flink/usrlib/" + manifest.get(0).path("filename").asText());
  }

  /** 将公共 PodTemplate 与 JM/TM 模板分别合并，避免依赖集群端的数组合并行为。 */
  public void normalize(ObjectNode spec) {
    if (!spec.path("podTemplate").isObject()) return;
    ObjectNode common = (ObjectNode) spec.get("podTemplate");
    for (String component : List.of("jobManager", "taskManager")) {
      ObjectNode manager = spec.withObject("/" + component);
      manager.set("podTemplate", merge(common.deepCopy(), manager.path("podTemplate")));
    }
    spec.remove("podTemplate");
  }

  /** 按 name 合并或新增数组元素，保留同名容器、环境变量或卷的其他配置。 */
  private void upsert(ObjectNode parent, String key, ObjectNode value) {
    ArrayNode list =
        parent.path(key).isArray() ? (ArrayNode) parent.get(key) : parent.putArray(key);
    for (int i = 0; i < list.size(); i++)
      if (list.get(i).path("name").equals(value.path("name"))) {
        list.set(i, merge((ObjectNode) list.get(i), value));
        return;
      }
    list.add(value);
  }

  /** 递归合并对象及具名数组，标量值由覆盖模板优先。 */
  private ObjectNode merge(ObjectNode target, JsonNode override) {
    if (!override.isObject()) return target;
    override
        .fields()
        .forEachRemaining(
            entry -> {
              String key = entry.getKey();
              JsonNode value = entry.getValue();
              if (value.isObject() && target.path(key).isObject())
                merge((ObjectNode) target.get(key), value);
              else if (value.isArray()
                  && target.path(key).isArray()
                  && value.size() > 0
                  && value.get(0).has("name"))
                for (JsonNode item : value) upsert(target, key, (ObjectNode) item);
              else target.set(key, value.deepCopy());
            });
    return target;
  }
}
