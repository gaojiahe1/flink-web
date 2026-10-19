package com.flinkweb.service.job.creation.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.config.platform.PlatformProperties;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.creation.support.PodTemplates;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** 安全解析和校验 FlinkDeployment YAML，应用目标 Namespace 并保留用户的运行账号和模板。 */
@Service
public class YamlServiceImpl implements YamlService {
  private final ObjectMapper mapper;
  private final String namespace;
  private final PodTemplates podTemplates;

  /** 注入 JSON 映射器、模板工具并读取目标 Namespace。 */
  public YamlServiceImpl(
      ObjectMapper mapper, PlatformProperties properties, PodTemplates podTemplates) {
    this.mapper = mapper;
    this.podTemplates = podTemplates;
    this.namespace = properties.namespace();
  }

  /** 限制 YAML 大小、标签、重复键及别名，将单个文档安全转换为对象。 */
  public ObjectNode parse(String source) {
    if (source == null || source.isBlank() || source.length() > 200000)
      fail("YAML 不能为空且长度不得超过 200KB");
    try {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setMaxAliasesForCollections(0);
      options.setNestingDepthLimit(40);
      options.setCodePointLimit(200000);
      Object loaded = new Yaml(new SafeConstructor(options)).load(source);
      JsonNode node = mapper.valueToTree(loaded);
      if (!node.isObject()) fail("YAML 顶层必须为对象");
      return (ObjectNode) node;
    } catch (BusinessException ex) {
      throw ex;
    } catch (Exception ex) {
      String position = "";
      if (ex instanceof org.yaml.snakeyaml.error.MarkedYAMLException marked
          && marked.getProblemMark() != null)
        position =
            "第 "
                + (marked.getProblemMark().getLine() + 1)
                + " 行，第 "
                + (marked.getProblemMark().getColumn() + 1)
                + " 列；";
      throw new BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_YAML", "YAML 解析失败：" + position + "检查缩进、重复键、多文档或不安全标签");
    }
  }

  /** 检查部署类型、Namespace、Flink 版本和作业参数，补充允许的默认配置。 */
  public ObjectNode validate(ObjectNode node, String expectedName) {
    if (!"flink.apache.org/v1beta1".equals(node.path("apiVersion").asText())
        || !"FlinkDeployment".equals(node.path("kind").asText()))
      fail("仅支持 flink.apache.org/v1beta1 FlinkDeployment");
    ObjectNode metadata = object(node, "metadata");
    String name = metadata.path("name").asText();
    if (!metadata.path("name").isTextual()
        || !name.matches("[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?")
        || (!expectedName.isEmpty() && !name.equals(expectedName)))
      fail("metadata.name 必须与作业名称一致，且是合法 DNS 名称");
    if (metadata.has("namespace") && !namespace.equals(metadata.path("namespace").asText()))
      fail("禁止操作其他 namespace");
    metadata.put("namespace", namespace);
    for (String field : List.of("labels", "annotations")) {
      if (metadata.has(field)) {
        var values = object(metadata, field);
        values
            .fields()
            .forEachRemaining(
                entry -> {
                  if (!entry.getValue().isTextual()) fail("metadata." + field + " 必须为字符串键值表");
                });
      }
    }
    metadata.remove(
        List.of(
            "uid",
            "resourceVersion",
            "generation",
            "managedFields",
            "creationTimestamp",
            "deletionTimestamp",
            "ownerReferences",
            "finalizers"));
    node.remove("status");
    ObjectNode spec = object(node, "spec");
    if (spec.has("mode") && !Set.of("native", "standalone").contains(spec.path("mode").asText()))
      fail("部署模式必须为 native 或 standalone");
    if (!spec.path("flinkVersion").asText().matches("v(1_(16|17|18|19|20)|2_[0-3])"))
      fail("Flink 版本必须在 1.16 至 2.3 范围内");
    if (spec.path("image").asText().isBlank()) fail("必须指定镜像");
    if (spec.has("serviceAccount")) validateServiceAccount(spec.get("serviceAccount"));
    ObjectNode job = object(spec, "job");
    if (!job.path("jarURI").asText().startsWith("local:///")
        || !job.path("jarURI").asText().toLowerCase().endsWith(".jar"))
      fail("jarURI 必须使用容器内 local:/// 路径；远程制品请通过下载配置挂载");
    if (!job.path("parallelism").isIntegralNumber()
        || !job.path("parallelism").canConvertToInt()
        || job.path("parallelism").asInt() < 1) fail("并行度必须为正整数");
    if (!Set.of("running", "suspended").contains(job.path("state").asText("running")))
      fail("作业状态无效");
    if (!Set.of("savepoint", "last-state", "stateless")
        .contains(job.path("upgradeMode").asText("savepoint"))) fail("升级模式无效");
    if (job.has("args")) {
      if (!job.path("args").isArray()) fail("args 必须为字符串数组");
      for (JsonNode arg : job.path("args")) if (!arg.isTextual()) fail("args 中的参数必须为字符串");
    }
    job.put("state", job.path("state").asText("running"));
    job.put("upgradeMode", job.path("upgradeMode").asText("savepoint"));
    ObjectNode config =
        spec.has("flinkConfiguration")
            ? object(spec, "flinkConfiguration")
            : spec.putObject("flinkConfiguration");
    if (config.has("kubernetes.namespace")
        && !config.path("kubernetes.namespace").asText().equals(namespace))
      fail("Flink 配置不能覆盖目标 namespace");
    if (config.has("kubernetes.cluster-id")
        && !config.path("kubernetes.cluster-id").asText().equals(name))
      fail("cluster-id 必须与作业名称一致");
    for (String key :
        List.of(
            "kubernetes.service-account",
            "kubernetes.jobmanager.service-account",
            "kubernetes.taskmanager.service-account"))
      if (config.has(key)) validateServiceAccount(config.get(key));
    for (String key : List.of("rest.port", "rest.bind-port"))
      if (config.has(key) && !config.path(key).asText().equals("8081"))
        fail("平台内部 Flink REST 端口固定为 8081");
    for (String key :
        List.of("state.checkpoints.num-retained", "execution.checkpointing.num-retained"))
      if (config.has(key) && !config.path(key).asText().matches("[1-9][0-9]*"))
        fail("flinkConfiguration: " + key + " 必须为正整数");
    for (String key :
        List.of(
            "state.checkpoints.dir",
            "state.savepoints.dir",
            "execution.checkpointing.dir",
            "execution.checkpointing.savepoint-dir"))
      if (config.has(key) && !config.path(key).asText().matches("[a-zA-Z][a-zA-Z0-9+.-]*:/.*"))
        fail("flinkConfiguration: " + key + " 必须是带协议的存储路径");
    String retention =
        config.path("execution.checkpointing.externalized-checkpoint-retention").asText();
    if (!retention.isEmpty()
        && !Set.of(
                "RETAIN_ON_CANCELLATION", "DELETE_ON_CANCELLATION", "NO_EXTERNALIZED_CHECKPOINTS")
            .contains(retention)) fail("flinkConfiguration: Checkpoint 存储策略无效");
    config
        .fields()
        .forEachRemaining(
            entry -> {
              if (!entry.getValue().isTextual())
                fail("flinkConfiguration 参数必须为字符串: " + entry.getKey());
            });
    for (String component : List.of("jobManager", "taskManager")) {
      if (spec.has(component) && !spec.path(component).isObject()) fail(component + " 必须为对象");
      JsonNode resource = spec.path(component).path("resource");
      if (spec.path(component).has("resource") && !resource.isObject())
        fail(component + ".resource 必须为对象");
      if (resource.isObject()) {
        if (resource.has("memory")
            && !resource
                .path("memory")
                .asText()
                .matches("(?i)[1-9][0-9]*(\\.[0-9]+)?\\s*(b|k|kb|kib|m|mb|mib|g|gb|gib|t|tb|tib)"))
          fail("内存格式无效，例如 1024m 或 2g");
        if (resource.has("cpu")
            && (!resource.path("cpu").isNumber()
                || !Double.isFinite(resource.path("cpu").asDouble())
                || resource.path("cpu").asDouble() <= 0)) fail("CPU 必须为大于 0 的数字");
      }
    }
    if (job.path("upgradeMode").asText().equals("last-state")
        && ((config.path("high-availability.type").asText("").isBlank()
                && config.path("high-availability").asText("").isBlank())
            || config.path("high-availability.storageDir").asText("").isBlank()))
      fail("upgradeMode: last-state 模式需要启用状态存储并配置 Kubernetes HA 及 HA 存储目录");
    podTemplates.validateStructure(spec);
    for (String key : List.of("podTemplate")) if (spec.has(key)) validatePod(spec.get(key));
    for (String key : List.of("jobManager", "taskManager"))
      if (spec.path(key).has("podTemplate")) validatePod(spec.get(key).get("podTemplate"));
    return node;
  }

  /** 校验 PodTemplate 的结构、Namespace 和 ServiceAccount 约束。 */
  private void validatePod(JsonNode pod) {
    if (pod.path("metadata").has("namespace")
        && !pod.path("metadata").path("namespace").asText().equals(namespace))
      fail("PodTemplate 不能指定其他 namespace");
    JsonNode spec = pod.path("spec");
    if (spec.has("serviceAccount")) validateServiceAccount(spec.get("serviceAccount"));
    for (JsonNode ref : spec.findValues("secretName"))
      if (ref.asText().startsWith("flink-web")) fail("工作负载不能挂载平台自身 Secret");
    for (String key : List.of("secretKeyRef", "secretRef"))
      for (JsonNode ref : spec.findValues(key))
        if (ref.path("name").asText().startsWith("flink-web")) fail("工作负载不能引用平台自身 Secret");
    for (String key : List.of("hostNetwork", "hostPID", "hostIPC"))
      if (spec.path(key).asBoolean()) fail("不允许 " + key);
    if (spec.has("serviceAccountName")) validateServiceAccount(spec.get("serviceAccountName"));
    for (JsonNode volume : spec.path("volumes")) if (volume.has("hostPath")) fail("不允许 hostPath 卷");
    for (String key : List.of("containers", "initContainers"))
      for (JsonNode container : spec.path(key)) {
        JsonNode security = container.path("securityContext");
        if (security.path("privileged").asBoolean() || security.path("capabilities").has("add"))
          fail("不允许特权容器或添加 Linux capabilities");
      }
  }

  /** 校验用户指定的运行账号名称，不替换为后端固定身份。 */
  private void validateServiceAccount(JsonNode value) {
    if (!value.isTextual()
        || value.asText().length() > 253
        || !value
            .asText()
            .matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?(\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*"))
      fail("serviceAccount: ServiceAccount 必须是合法 DNS 子域名称");
  }

  /** 将 JSON 资源对象序列化为可编辑的 YAML。 */
  public String dump(JsonNode node) {
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    return new Yaml(options).dump(ordered(node, ""));
  }

  /** 部署结构按阅读顺序稳定输出，未知字段、配置值、列表及数据类型原样保留。 */
  private Object ordered(JsonNode node, String path) {
    if (node.isArray()) {
      var values = new ArrayList<Object>();
      for (var child : node) values.add(ordered(child, path + "[]"));
      return values;
    }
    if (!node.isObject()) return mapper.convertValue(node, Object.class);
    List<String> preferred = switch (path) {
      case "" -> List.of("apiVersion", "kind", "metadata", "spec", "status");
      case "/metadata" -> List.of("name", "namespace", "labels", "annotations");
      case "/spec" -> List.of("image", "imagePullPolicy", "flinkVersion", "mode", "serviceAccount",
          "flinkConfiguration", "jobManager", "taskManager", "podTemplate", "job");
      case "/spec/job" -> List.of("jarURI", "entryClass", "args", "parallelism", "upgradeMode", "state");
      case "/spec/jobManager", "/spec/taskManager" -> List.of("replicas", "resource", "podTemplate");
      default -> List.of();
    };
    Map<String, Object> values = new LinkedHashMap<>();
    for (String key : preferred)
      if (node.has(key)) values.put(key, ordered(node.get(key), path + "/" + key));
    node.fields().forEachRemaining(entry -> values.putIfAbsent(entry.getKey(), ordered(entry.getValue(), path + "/" + entry.getKey())));
    return values;
  }

  /** 读取并验证指定字段为对象，缺少时按校验规则处理。 */
  public ObjectNode object(JsonNode parent, String key) {
    if (!parent.path(key).isObject()) fail(key + " 必须为对象");
    return (ObjectNode) parent.get(key);
  }

  /** 抛出明确的 YAML 校验错误。 */
  public void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_YAML", message);
  }
}
