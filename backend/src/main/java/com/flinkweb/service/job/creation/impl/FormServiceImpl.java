package com.flinkweb.service.job.creation.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.artifact.support.ArtifactPodBuilder;
import com.flinkweb.service.job.creation.FormService;
import com.flinkweb.service.job.creation.YamlService;
import org.springframework.stereotype.Service;

/** 将表单输入与默认模板、镜像版本、PodTemplate 和制品配置合成为 YAML。 */
@Service
public class FormServiceImpl implements FormService {
  private final YamlService yaml;
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final PlatformProperties properties;
  private final ArtifactPodBuilder artifacts;
  private final com.flinkweb.service.artifact.support.ArtifactReferences references;
  private final com.flinkweb.service.job.creation.support.S3PluginPodBuilder s3Plugin;

  /** 注入模板、镜像及制品渲染依赖，并读取工作负载默认参数。 */
  public FormServiceImpl(
      YamlService yaml,
      RecordRepository records,
      ObjectMapper mapper,
      PlatformProperties properties,
      ArtifactPodBuilder artifacts,
      com.flinkweb.service.artifact.support.ArtifactReferences references,
      com.flinkweb.service.job.creation.support.S3PluginPodBuilder s3Plugin) {
    this.s3Plugin = s3Plugin;
    this.yaml = yaml;
    this.records = records;
    this.mapper = mapper;
    this.properties = properties;
    this.artifacts = artifacts;
    this.references = references;
  }

  /** 读取模板和镜像版本，填入表单参数、PodTemplate 及制品下载配置，返回 YAML。 */
  public ObjectNode render(ObjectNode input) {
    checkInput(input);
    String name = input.path("name").asText(), template = input.path("templateId").asText("");
    ObjectNode resource =
        input.path("baseYaml").asText().isBlank()
            ? template.isEmpty()
                ? mapper.createObjectNode()
                : yaml.parse(records.require("templates", template).path("yaml").asText())
            : yaml.parse(input.path("baseYaml").asText());
    com.flinkweb.service.job.creation.support.JobEditorPolicy.mark(resource, true);
    resource.put("apiVersion", "flink.apache.org/v1beta1").put("kind", "FlinkDeployment");
    resource.withObject("/metadata").put("name", name).put("namespace", properties.namespace());
    ObjectNode spec = resource.withObject("/spec");
    if (input.has("logConfiguration")) {
      if (input.path("logConfiguration").isEmpty()) spec.remove("logConfiguration");
      else spec.set("logConfiguration", input.get("logConfiguration").deepCopy());
    }
    if (input.has("deploymentMode")) spec.put("mode", input.path("deploymentMode").asText());
    if (input.has("tmReplicas")) {
      if (input.path("tmReplicas").isNull()) spec.withObject("/taskManager").remove("replicas");
      else spec.withObject("/taskManager").set("replicas", input.get("tmReplicas"));
    }
    if (input.has("serviceAccount"))
      spec.set("serviceAccount", input.get("serviceAccount").deepCopy());
    var image = records.require("images", input.path("imageId").asText());
    String version = image.path("flinkVersion").asText().replaceAll("^(\\d+\\.\\d+).*", "$1");
    spec.put("image", image.path("repository").asText() + (":" + image.path("version").asText()));
    spec.put("flinkVersion", "v" + version.replace('.', '_'));
    ObjectNode job = spec.withObject("/job");
    job.put("jarURI", input.path("jarURI").asText("local:///opt/flink/usrlib/job.jar"))
        .put("parallelism", input.path("parallelism").asInt(1))
        .put("state", job.path("state").asText("running"))
        .put("upgradeMode", input.path("upgradeMode").asText("savepoint"));
    if (!input.path("entryClass").asText().isEmpty())
      job.put("entryClass", input.path("entryClass").asText());
    else job.remove("entryClass");
    if (input.path("args").isArray()) job.set("args", input.get("args"));
    applyResource(spec, input, "jobManager", "jm");
    applyResource(spec, input, "taskManager", "tm");
    if (input.path("flinkConfiguration").isObject())
      spec.set("flinkConfiguration", input.get("flinkConfiguration").deepCopy());
    spec.withObject("/flinkConfiguration")
        .put("taskmanager.numberOfTaskSlots", input.path("slots").asText("1"));
    if (input.path("baseYaml").asText().isBlank() && template.isEmpty())
      spec.withObject("/flinkConfiguration")
          .putIfAbsent("jobmanager.memory.jvm-metaspace.size", TextNode.valueOf("512m"));

    applyPodTemplate(input, "podTemplate", spec);
    applyPodTemplate(input, "jmPodTemplate", spec.withObject("/jobManager"));
    applyPodTemplate(input, "tmPodTemplate", spec.withObject("/taskManager"));
    if (!image.path("pullSecret").asText("").isEmpty())
      spec.withObject("/podTemplate")
          .withObject("/spec")
          .putArray("imagePullSecrets")
          .addObject()
          .put("name", image.path("pullSecret").asText());
    ArrayNode downloads = downloads(input);
    if (downloads.isEmpty()) artifacts.clearManaged(spec);
    if (!downloads.isEmpty()) {
      String originalJar = job.path("jarURI").asText();
      if (input.path("mainArtifactId").asText().isBlank()
          && !input.has("artifactIds")
          && originalJar.startsWith("local:///opt/flink/usrlib/"))
        yaml.fail("附加依赖会挂载 /opt/flink/usrlib；请同时选择主 JAR，或将镜像内主 JAR 放到该目录之外");
      artifacts.apply(spec, downloads);
      // 只有附加依赖时保留镜像内主 JAR，避免把连接器误当成程序入口。
      if (input.path("mainArtifactId").asText().isBlank() && !input.has("artifactIds"))
        job.put("jarURI", originalJar);
    }
    s3Plugin.apply(spec);
    // 保留模板变量引用，在用户提交部署时解析当前配置。
    return mapper
        .createObjectNode()
        .put("yaml", yaml.dump(resource))
        .put("flinkVersion", image.path("flinkVersion").asText());
  }

  /** YAML 中省略的资源字段保持省略，避免覆盖由 Flink 配置或 Operator 提供的资源。 */
  private void applyResource(ObjectNode spec, ObjectNode input, String component, String prefix) {
    boolean preserve = !input.path("deploymentYaml").asText().isBlank();
    if (preserve && !input.has(prefix + "Memory") && !input.has(prefix + "Cpu")) return;
    var target = spec.withObject("/" + component + "/resource");
    if (!preserve || input.has(prefix + "Memory"))
      target.put("memory", input.path(prefix + "Memory").asText("2048m"));
    if (!preserve || input.has(prefix + "Cpu"))
      target.put("cpu", input.path(prefix + "Cpu").asDouble(1));
  }

  /** 在生成 YAML 前校验表单字段，避免非整数并行度被隐式截断。 */
  private void checkInput(ObjectNode input) {
    if (input.has("deploymentMode")
        && !java.util.Set.of("native", "standalone")
            .contains(input.path("deploymentMode").asText()))
      yaml.fail("部署模式必须为 native 或 standalone");
    if (input.hasNonNull("tmReplicas")) {
      var replicas = input.path("tmReplicas");
      if (!replicas.isIntegralNumber() || !replicas.canConvertToInt() || replicas.asInt() < 1)
        yaml.fail("TaskManager 数量必须为正整数");
      if (!"standalone".equals(input.path("deploymentMode").asText()))
        yaml.fail("固定 TaskManager 数量仅适用于 standalone 模式；native 模式按需分配");
    }
    if (input.path("imageId").asText().isBlank()) yaml.fail("请选择已登记的运行镜像");
    if ("savepoint".equals(input.path("upgradeMode").asText())
        && input.at("/flinkConfiguration/state.savepoints.dir").asText().isBlank()
        && input.at("/flinkConfiguration/execution.checkpointing.savepoint-dir").asText().isBlank())
      yaml.fail("upgradeMode: savepoint 模式需要配置 Savepoint 存储目录；无状态作业请选择 stateless");
    for (String key : java.util.List.of("parallelism", "slots")) {
      JsonNode value = input.get(key);
      if (value != null
          && (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1))
        yaml.fail(key + " 必须为正整数");
    }
    for (String key : java.util.List.of("jmCpu", "tmCpu")) {
      JsonNode value = input.get(key);
      if (value != null
          && (!value.isNumber() || !Double.isFinite(value.asDouble()) || value.asDouble() <= 0))
        yaml.fail(key + " 必须为大于 0 的数字");
    }
    String entry = input.path("entryClass").asText();
    if (!entry.isEmpty()
        && !entry.matches("[a-zA-Z_$][a-zA-Z0-9_$]*(\\.[a-zA-Z_$][a-zA-Z0-9_$]*)*"))
      yaml.fail("入口类必须是合法 Java 类全名");
    if (input.has("args")) {
      if (!input.path("args").isArray()) yaml.fail("程序参数必须是字符串数组");
      for (JsonNode argument : input.path("args"))
        if (!argument.isTextual()) yaml.fail("每个程序参数必须是字符串");
    }
    if (input.has("serviceAccount")
        && (!input.path("serviceAccount").isTextual()
            || input.path("serviceAccount").asText().isBlank()))
      yaml.fail("serviceAccount: 请填写 ServiceAccount");
    for (String key :
        java.util.List.of("flinkConfiguration", "logConfiguration", "podTemplate", "jmPodTemplate", "tmPodTemplate"))
      if (input.has(key) && !input.path(key).isObject()) yaml.fail(key + " 必须是 JSON 对象");
    if (input.has("logConfiguration")) {
      var fields = input.path("logConfiguration").fields();
      while (fields.hasNext()) {
        var field = fields.next();
        if (field.getKey().isBlank() || !field.getValue().isTextual())
          yaml.fail("logConfiguration 必须为文件名到文本的映射");
      }
    }
  }

  /** 显式空对象移除对应模板；未提交的字段保留原 YAML，角色模板互不覆盖。 */
  private void applyPodTemplate(ObjectNode input, String key, ObjectNode target) {
    if (!input.has(key)) return;
    if (input.path(key).isEmpty()) target.remove("podTemplate");
    else target.set("podTemplate", input.get(key).deepCopy());
  }

  /** 明确区分主 JAR 与附加文件，同时兼容旧版首项为主 JAR 的数组输入。 */
  private ArrayNode downloads(ObjectNode input) {
    ArrayNode result = mapper.createArrayNode();
    String main = input.path("mainArtifactId").asText();
    if (input.has("artifactIds") && (!main.isBlank() || input.has("dependencyArtifactIds")))
      yaml.fail("不能同时使用旧 artifactIds 与新的主 JAR/附加依赖参数");
    JsonNode dependencies =
        input.path(input.has("artifactIds") ? "artifactIds" : "dependencyArtifactIds");
    if (!dependencies.isMissingNode() && !dependencies.isArray()) yaml.fail("附加依赖必须为制品标识数组");
    if (!main.isBlank()) result.add(main);
    if (dependencies.isArray())
      for (JsonNode dependency : dependencies) {
        if (!dependency.isTextual() || dependency.asText().isBlank()) yaml.fail("制品标识不能为空");
        result.add(dependency);
      }
    if (result.size() > 50) yaml.fail("最多选择 50 个制品");
    var unique = new java.util.HashSet<String>();
    for (JsonNode id : result) if (!unique.add(id.asText())) yaml.fail("主 JAR 和附加依赖不能重复选择");
    if (!main.isBlank() || (input.has("artifactIds") && !result.isEmpty())) {
      String reference = result.get(0).asText();
      String filename = references.describe(reference).path("filename").asText();
      if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
        yaml.fail("主制品必须是 .jar 文件；源码和配置文件只能作为附加文件");
    }
    return result;
  }
}
