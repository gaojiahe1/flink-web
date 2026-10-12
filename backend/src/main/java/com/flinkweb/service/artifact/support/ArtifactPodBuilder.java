package com.flinkweb.service.artifact.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.support.PodTemplates;
import com.flinkweb.service.system.DownloaderSettingsService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 构建公共下载器模板，并在预览、部署和重建时统一平台托管配置。 */
@Component
public class ArtifactPodBuilder {
  private final ObjectMapper mapper;
  private final com.flinkweb.client.storage.S3StorageClient storage;
  private final RecordRepository records;
  private final DownloaderSettingsService settings;
  private final PodTemplates templates;
  private final ArtifactReferences references;
  private final String hosts, endpoint, credentialsSecret;
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(ArtifactPodBuilder.class);

  /** 注入文件访问、唯一镜像绑定及凭据配置。 */
  public ArtifactPodBuilder(
      ObjectMapper mapper,
      RecordRepository records,
      com.flinkweb.client.storage.S3StorageClient storage,
      DownloaderSettingsService settings,
      PodTemplates templates,
      ArtifactReferences references,
      @Value("${platform.artifacts.allowed-hosts:}") String hosts,
      @Value("${platform.s3.endpoint:}") String endpoint,
      @Value("${platform.artifacts.credentials-secret:}") String credentialsSecret) {
    this.mapper = mapper;
    this.records = records;
    this.storage = storage;
    this.settings = settings;
    this.templates = templates;
    this.references = references;
    this.hosts = hosts;
    this.endpoint = endpoint;
    this.credentialsSecret = credentialsSecret;
  }

  /** 生成无需校验和的下载清单，只保留文件地址和必要的鉴权引用。 */
  public void apply(ObjectNode spec, JsonNode ids) {
    if (ids.isEmpty()) {
      clearManaged(spec);
      return;
    }
    ArrayNode manifest = mapper.createArrayNode(), env = mapper.createArrayNode();
    Set<String> names = new HashSet<>();
    int index = 0;
    for (JsonNode id : ids) {
      var artifact = references.describe(id.asText());
      String filename = artifact.path("filename").asText();
      if (!names.add(filename)) fail("附加文件名重复：" + filename);
      var entry =
          manifest.addObject().put("uri", artifact.path("uri").asText()).put("filename", filename);
      if (!artifact.path("credentialRef").asText().isBlank()) {
        String variable = "ARTIFACT_TOKEN_" + index++;
        entry.put("tokenEnv", variable);
        env.addObject()
            .put("name", variable)
            .put("value", "{{secret:" + artifact.path("credentialRef").asText() + "}}");
      }
    }
    install(spec, manifest, env);
    spec.withObject("/job")
        .put("jarURI", "local:///opt/flink/usrlib/" + manifest.get(0).path("filename").asText());
  }

  /** 安装唯一下载器，按目标分配独立卷，插件不会遮蔽镜像内主 JAR。 */
  public void install(ObjectNode spec, ArrayNode manifest, ArrayNode env) {
    clearManaged(spec);
    if (manifest.isEmpty() && !bootstrapEnabled(env)) return;
    templates.validateStructure(spec);
    templates.configureManagedMerge(spec);
    configureEnvironment(env, manifest);
    ObjectNode pod = spec.withObject("/podTemplate/spec");
    ObjectNode init =
        mapper
            .createObjectNode()
            .put("name", "fw-download")
            .put("image", settings.requireImage())
            .put("imagePullPolicy", "Always");
    init.set("env", env.deepCopy());
    init.putObject("securityContext")
        .put("allowPrivilegeEscalation", false)
        .put("runAsNonRoot", true)
        .put("runAsUser", 9999)
        .put("readOnlyRootFilesystem", true);
    init.withObject("/resources/limits").put("memory", "256Mi").put("cpu", "500m");
    var destinations = new HashSet<String>();
    for (JsonNode entry : manifest) {
      String target = entry.path("target").asText("artifacts");
      if (!Set.of("artifacts", "s3-plugin").contains(target)) fail("下载目标无效");
      if (!destinations.add(target)) continue;
      String name = target.equals("artifacts") ? "fw-artifacts" : "fw-s3-plugin";
      String path =
          target.equals("artifacts") ? "/opt/flink/usrlib" : "/opt/flink/plugins/s3-fs-presto";
      templates.checkManagedMount(spec, path, name);
      for (JsonNode container : pod.path("containers"))
        if ("flink-main-container".equals(container.path("name").asText()))
          for (JsonNode mount : container.path("volumeMounts"))
            if (path.equals(mount.path("mountPath").asText())
                && !name.equals(mount.path("name").asText()))
              fail("podTemplate: 目录已有自定义挂载：" + path);
      ObjectNode volume = mapper.createObjectNode().put("name", name);
      volume.putObject("emptyDir").put("sizeLimit", "2Gi");
      templates.upsert(pod, "volumes", volume);
      init.withArray("volumeMounts").addObject().put("name", name).put("mountPath", "/" + target);
      ObjectNode main = mapper.createObjectNode().put("name", "flink-main-container");
      main.putArray("volumeMounts")
          .addObject()
          .put("name", name)
          .put("mountPath", path)
          .put("readOnly", true);
      templates.upsert(pod, "containers", main);
    }
    templates.upsert(pod, "initContainers", init);
    pod.withObject("/securityContext").putIfAbsent("fsGroup", IntNode.valueOf(9999));
    log.debug("Unified download template generated files={}", manifest.size());
  }

  /** 允许只准备镜像内插件启动脚本而不下载文件。 */
  private boolean bootstrapEnabled(JsonNode env) {
    for (JsonNode item : env)
      if ("FLINK_PLUGIN_BOOTSTRAP".equals(item.path("name").asText())
          && "true".equals(item.path("value").asText())) return true;
    return false;
  }

  /** 检查下载清单是否含 S3 文件，避免向 HTTP 下载器注入多余凭据。 */
  private boolean usesS3(JsonNode manifest) {
    for (JsonNode item : manifest) if (item.path("uri").asText().startsWith("s3://")) return true;
    return false;
  }

  /** 设置清单、S3 签名区域和按需凭据引用，不向模板写入密钥明文。 */
  private void configureEnvironment(ArrayNode env, ArrayNode manifest) {
    Set<String> managed =
        Set.of(
            "ARTIFACT_MANIFEST",
            "ARTIFACT_ALLOWED_HOSTS",
            "S3_ENDPOINT",
            "AWS_DEFAULT_REGION",
            "AWS_REGION",
            "AWS_ACCESS_KEY_ID",
            "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN");
    for (int i = env.size() - 1; i >= 0; i--)
      if (managed.contains(env.get(i).path("name").asText())) env.remove(i);
    for (JsonNode item : manifest) if (item.isObject()) ((ObjectNode) item).remove("sha256");
    env.addObject().put("name", "ARTIFACT_MANIFEST").put("value", manifest.toString());
    env.addObject().put("name", "ARTIFACT_ALLOWED_HOSTS").put("value", hosts);
    if (usesS3(manifest)) {
      env.addObject().put("name", "S3_ENDPOINT").put("value", endpoint);
      env.addObject()
          .put("name", "AWS_REGION")
          .put("value", storage.defaults().path("region").asText("us-east-1"));
      for (String key : List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")) {
        var variable = env.addObject().put("name", key);
        if (!credentialsSecret.isBlank())
          variable
              .putObject("valueFrom")
              .putObject("secretKeyRef")
              .put("name", credentialsSecret)
              .put("key", key);
        else
          variable.put(
              "value",
              key.equals("AWS_ACCESS_KEY_ID")
                  ? "{{secret:s3-access-key}}"
                  : "{{secret:s3-secret-key}}");
      }
    }
  }

  /** 在渲染前更新历史下载器，消除配置镜像和实际部署镜像不一致。 */
  public void prepare(ObjectNode spec) {
    for (String role : List.of("", "jobManager", "taskManager")) {
      JsonNode pod = (role.isEmpty() ? spec : spec.path(role)).path("podTemplate").path("spec");
      for (JsonNode container : pod.path("initContainers")) {
        if (!"fw-download".equals(container.path("name").asText()) || !container.isObject())
          continue;
        var init = (ObjectNode) container;
        ArrayNode env = init.withArray("env");
        String value = "";
        for (JsonNode item : env)
          if ("ARTIFACT_MANIFEST".equals(item.path("name").asText()))
            value = item.path("value").asText();
        try {
          var manifest = mapper.readTree(value);
          if (!(manifest instanceof ArrayNode list) || list.isEmpty() && !bootstrapEnabled(env)) {
            fail("下载清单为空或格式错误，请重新选择依赖文件");
            return;
          }
          for (JsonNode entry : list)
            if (!entry.isObject()
                || entry.path("uri").asText().isBlank()
                || entry.path("filename").asText().isBlank()) fail("下载清单缺少地址或文件名");
          configureEnvironment(env, list);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
          fail("下载清单格式错误，请重新选择依赖文件");
        }
        init.put("image", settings.requireImage());
      }
    }
    templates.validateStructure(spec);
  }

  /** 拒绝无法生成下载配置的输入。 */
  private void fail(String message) {
    throw new com.flinkweb.exception.common.BusinessException(
        org.springframework.http.HttpStatus.BAD_REQUEST, "INVALID_ARTIFACT", message);
  }

  /** 仅移除平台生成的下载器、挂载和卷，保留用户的其他模板字段。 */
  public void clearManaged(ObjectNode spec) {
    for (String component : List.of("", "jobManager", "taskManager")) {
      JsonNode pod =
          (component.isEmpty() ? spec : spec.path(component)).path("podTemplate").path("spec");
      if (!pod.isObject()) continue;
      removeNamed((ObjectNode) pod, "initContainers", "fw-download");
      removeNamed((ObjectNode) pod, "volumes", "fw-artifacts");
      for (JsonNode container : pod.path("containers"))
        if (container.isObject())
          removeNamed((ObjectNode) container, "volumeMounts", "fw-artifacts");
    }
  }

  /** 从具名数组中删除平台独占的元素。 */
  private void removeNamed(ObjectNode parent, String field, String name) {
    if (parent.path(field).isArray()) {
      ArrayNode items = (ArrayNode) parent.get(field);
      for (int i = items.size() - 1; i >= 0; i--)
        if (name.equals(items.get(i).path("name").asText())) items.remove(i);
    }
  }
}
