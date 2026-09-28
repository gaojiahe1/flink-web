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

  /** 注入模板、镜像及制品渲染依赖，并读取工作负载默认参数。 */
  public FormServiceImpl(
      YamlService yaml,
      RecordRepository records,
      ObjectMapper mapper,
      PlatformProperties properties,
      ArtifactPodBuilder artifacts) {
    this.yaml = yaml;
    this.records = records;
    this.mapper = mapper;
    this.properties = properties;
    this.artifacts = artifacts;
  }

  /** 读取模板和镜像版本，填入表单参数、PodTemplate 及制品下载配置，返回 YAML。 */
  public ObjectNode render(ObjectNode input) {
    checkInput(input);
    String name = input.path("name").asText(), template = input.path("templateId").asText("");
    ObjectNode resource =
        template.isEmpty()
            ? mapper.createObjectNode()
            : yaml.parse(records.require("templates", template).path("yaml").asText());
    resource.put("apiVersion", "flink.apache.org/v1beta1").put("kind", "FlinkDeployment");
    resource.withObject("/metadata").put("name", name).put("namespace", properties.namespace());
    ObjectNode spec = resource.withObject("/spec");
    var image = records.require("images", input.path("imageId").asText());
    String version = image.path("flinkVersion").asText().replaceAll("^(\\d+\\.\\d+).*", "$1");
    spec.put("image", image.path("repository").asText() + (":" + image.path("version").asText()));
    spec.put("flinkVersion", "v" + version.replace('.', '_'));
    ObjectNode job = spec.withObject("/job");
    job.put("jarURI", input.path("jarURI").asText("local:///opt/flink/usrlib/job.jar"))
        .put("parallelism", input.path("parallelism").asInt(1))
        .put("state", "running")
        .put("upgradeMode", input.path("upgradeMode").asText("savepoint"));
    if (!input.path("entryClass").asText().isEmpty())
      job.put("entryClass", input.path("entryClass").asText());
    else job.remove("entryClass");
    if (input.path("args").isArray()) job.set("args", input.get("args"));
    spec.withObject("/jobManager")
        .withObject("/resource")
        .put("memory", input.path("jmMemory").asText("1024m"))
        .put("cpu", input.path("jmCpu").asDouble(1));
    spec.withObject("/taskManager")
        .withObject("/resource")
        .put("memory", input.path("tmMemory").asText("2048m"))
        .put("cpu", input.path("tmCpu").asDouble(1));
    spec.withObject("/flinkConfiguration")
        .put("taskmanager.numberOfTaskSlots", input.path("slots").asText("1"));
    if (input.path("flinkConfiguration").isObject())
      spec.withObject("/flinkConfiguration").setAll((ObjectNode) input.get("flinkConfiguration"));
    if (input.path("podTemplate").isObject())
      spec.set("podTemplate", input.get("podTemplate").deepCopy());
    if (!image.path("pullSecret").asText("").isEmpty())
      spec.withObject("/podTemplate")
          .withObject("/spec")
          .putArray("imagePullSecrets")
          .addObject()
          .put("name", image.path("pullSecret").asText());
    ArrayNode downloads = downloads(input);
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
    // 保留模板变量引用，在用户提交部署时解析当前配置。
    return mapper
        .createObjectNode()
        .put("yaml", yaml.dump(resource))
        .put("flinkVersion", image.path("flinkVersion").asText());
  }

  /** 在生成 YAML 前校验表单字段，避免非整数并行度被隐式截断。 */
  private void checkInput(ObjectNode input) {
    if (input.path("imageId").asText().isBlank()) yaml.fail("请选择已登记的运行镜像");
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
    for (String key : java.util.List.of("flinkConfiguration", "podTemplate"))
      if (input.has(key) && !input.path(key).isObject()) yaml.fail(key + " 必须是 JSON 对象");
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
      var artifact = records.require("artifacts", result.get(0).asText());
      if (!artifact.path("filename").asText().toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
        yaml.fail("主制品必须是 .jar 文件；源码和配置文件只能作为附加文件");
    }
    return result;
  }
}
