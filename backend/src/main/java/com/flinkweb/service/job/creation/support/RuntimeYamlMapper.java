package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.YamlService;
import java.util.List;
import org.springframework.stereotype.Component;

/** 将 SQL/Session YAML 的可编辑字段回填为运行参数，并保留表单之外的配置。 */
@Component
public class RuntimeYamlMapper {
  private final YamlService yaml;
  private final RecordRepository records;

  /** 复用部署安全校验和已登记镜像，不接受客户端推断的镜像版本。 */
  public RuntimeYamlMapper(YamlService yaml, RecordRepository records) {
    this.yaml = yaml;
    this.records = records;
  }

  /** 表单不能安全表达省略字段或不支持的内存单位时，允许保存 YAML 但阻止破坏性回填。 */
  public String formIssue(ObjectNode runtime) {
    String source = runtime.path("deploymentYaml").asText();
    if (source.isBlank()) return "";
    var spec = yaml.parse(source).path("spec");
    if (!spec.path("serviceAccount").isTextual())
      return "YAML 未指定 ServiceAccount，无法无损转换为当前表单；请继续使用 YAML 或显式指定账号";
    for (String component : List.of("jobManager", "taskManager")) {
      var resource = spec.path(component).path("resource");
      if (!resource.path("memory").isTextual()
          || !resource.path("cpu").isNumber()
          || !resource.path("memory").asText().matches("(?i)[1-9][0-9]*(\\.[0-9]+)?[mg]"))
        return "spec." + component + ".resource 无法无损映射为当前表单，请继续使用 YAML";
    }
    return "";
  }

  /** YAML 模式以原文为准；表单模式通过 baseYaml 保留未映射字段。 */
  public ObjectNode prepare(ObjectNode runtime, String name, boolean session) {
    var options = runtime.deepCopy();
    String source = options.path("deploymentYaml").asText();
    if (source.isBlank()) return options;
    var node = yaml.parse(source);
    var spec = yaml.object(node, "spec");
    if (session) {
      if (spec.has("job")) yaml.fail("Session YAML 不允许包含 spec.job");
      spec.putObject("job")
          .put("jarURI", "local:///opt/flink/lib/session-placeholder.jar")
          .put("parallelism", 1)
          .put("upgradeMode", "stateless");
    }
    yaml.validate(node, name);
    if (session) spec.remove("job");
    var image =
        records.list("images").stream()
            .filter(
                i ->
                    (i.path("repository").asText() + ":" + i.path("version").asText())
                        .equals(spec.path("image").asText()))
            .filter(
                i ->
                    ("v"
                            + i.path("flinkVersion")
                                .asText()
                                .replaceAll("^(\\d+\\.\\d+).*", "$1")
                                .replace('.', '_'))
                        .equals(spec.path("flinkVersion").asText()))
            .min(
                java.util.Comparator.comparing(
                    i -> !runtime.path("imageId").asText().equals(i.path("id").asText())));
    if (image.isEmpty()) yaml.fail("YAML 镜像及 Flink 版本必须匹配已登记的运行镜像");
    options.put("imageId", image.orElseThrow().path("id").asText());
    options.put("baseYaml", source);
    if (spec.has("logConfiguration") && (!spec.path("logConfiguration").isObject()
        || !java.util.stream.StreamSupport.stream(spec.path("logConfiguration").spliterator(), false).allMatch(JsonNode::isTextual)))
      yaml.fail("logConfiguration 必须为文件名到文本的映射");
    options.set("logConfiguration", spec.has("logConfiguration") ? spec.get("logConfiguration").deepCopy() : options.objectNode());
    if (spec.has("serviceAccount")) options.set("serviceAccount", spec.get("serviceAccount"));
    else options.remove("serviceAccount");
    options.set("flinkConfiguration", spec.path("flinkConfiguration").deepCopy());
    String slots = spec.at("/flinkConfiguration/taskmanager.numberOfTaskSlots").asText("1");
    try {
      int count = Integer.parseInt(slots);
      if (count < 1) throw new NumberFormatException();
      options.put("slots", count);
    } catch (NumberFormatException ex) {
      yaml.fail("taskmanager.numberOfTaskSlots 必须为正整数");
    }
    options.put("deploymentMode", spec.path("mode").asText("native"));
    if (spec.path("taskManager").has("replicas"))
      options.set("tmReplicas", spec.at("/taskManager/replicas"));
    else options.putNull("tmReplicas");
    for (String component : List.of("jobManager", "taskManager")) {
      String prefix = component.equals("jobManager") ? "jm" : "tm";
      var resource = spec.path(component).path("resource");
      if (resource.has("memory")) options.set(prefix + "Memory", resource.get("memory"));
      else options.remove(prefix + "Memory");
      if (resource.has("cpu")) options.set(prefix + "Cpu", resource.get("cpu"));
      else options.remove(prefix + "Cpu");
      options.set(
          prefix + "PodTemplate",
          spec.path(component).path("podTemplate").isMissingNode()
              ? options.objectNode()
              : spec.path(component).path("podTemplate").deepCopy());
    }
    options.set(
        "podTemplate",
        spec.path("podTemplate").isMissingNode()
            ? options.objectNode()
            : spec.path("podTemplate").deepCopy());
    if (!session) {
      options.set("parallelism", spec.at("/job/parallelism"));
      options.put("upgradeMode", spec.at("/job/upgradeMode").asText());
      if (spec.at("/job/args").isArray() && !spec.at("/job/args").isEmpty())
        yaml.fail("SQL YAML 的 spec.job.args 由 SQL 脚本生成，请在 SQL 编辑器修改脚本");
    }
    return options;
  }

  /** 阻止 YAML 修改被平台重新生成覆盖的 Runner、身份及下载器配置。 */
  public void verifyManaged(ObjectNode runtime, ObjectNode generated, boolean session) {
    if (runtime.path("deploymentYaml").asText().isBlank()) return;
    var source = yaml.parse(runtime.path("deploymentYaml").asText());
    for (String pointer :
        List.of(
            "/metadata/labels/flink-web~1session-id",
            "/metadata/labels/flink-web~1sql-id",
            "/metadata/labels/flink-web~1job-type",
            "/spec/flinkConfiguration/pipeline.name")) {
      if (!source.at(pointer).isMissingNode() && !source.at(pointer).equals(generated.at(pointer)))
        yaml.fail("YAML 平台管理字段不可修改: " + pointer);
    }
    if (!session) {
      for (String key : List.of("jarURI", "entryClass"))
        if (!source.at("/spec/job/" + key).equals(generated.at("/spec/job/" + key)))
          yaml.fail("spec.job." + key + " 由已选择的 SQL Runner 管理，请在表单中修改 Runner");
    }
    for (String pointer :
        List.of(
            "/spec/podTemplate/spec",
            "/spec/jobManager/podTemplate/spec",
            "/spec/taskManager/podTemplate/spec")) {
      for (String field : List.of("initContainers", "volumes")) {
        for (JsonNode item : source.at(pointer).path(field)) {
          String name = item.path("name").asText();
          if (!List.of("fw-download", "fw-artifacts").contains(name)) continue;
          JsonNode expected = null;
          for (JsonNode candidate : generated.at(pointer).path(field))
            if (name.equals(candidate.path("name").asText())) expected = candidate;
          if (!item.equals(expected))
            yaml.fail("YAML 托管下载配置不可修改: " + pointer + "/" + field + "/" + name);
        }
      }
    }
  }
}
