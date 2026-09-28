package com.flinkweb.service.system.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.service.system.OperatorService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/** 查询运维部署的 Operator，并用随应用发布的官方版本资料判定兼容范围。 */
@Service
public class OperatorServiceImpl implements OperatorService {
  private final KubernetesClient client;
  private final ObjectMapper mapper;
  private final String namespace, deployment, container;
  private final JsonNode matrix;

  /** 读取固定查询目标及静态兼容资料，不访问 Kubernetes。 */
  public OperatorServiceImpl(
      KubernetesClient client,
      ObjectMapper mapper,
      @Value("${platform.operator.namespace:${platform.namespace}}") String namespace,
      @Value("${platform.operator.deployment:flink-kubernetes-operator}") String deployment,
      @Value("${platform.operator.container:flink-kubernetes-operator}") String container)
      throws java.io.IOException {
    this.client = client;
    this.mapper = mapper;
    this.namespace = namespace;
    this.deployment = deployment;
    this.container = container;
    if (!namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
        || !deployment.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?"))
      throw new IllegalArgumentException("Invalid operator resource name");
    try (var in = new ClassPathResource("operator-compatibility.json").getInputStream()) {
      matrix = mapper.readTree(in);
    }
  }

  /** 从明确的版本标签识别发行版；自定义标签、摘要及未知版本不猜测兼容性。 */
  public ObjectNode compatibility(String image) {
    var result = mapper.createObjectNode().put("known", false).put("version", "");
    var matcher =
        java.util.regex.Pattern.compile(":v?(1\\.(?:[7-9]|1[0-6])\\.\\d+)$").matcher(image);
    if (!matcher.find()) return result.put("message", "无法识别 Operator 发行版本，请核实 Deployment 镜像标签");
    String version = matcher.group(1);
    var entry = matrix.path(version.substring(0, version.lastIndexOf('.')));
    result.put("version", version).put("known", true);
    result.set("supported", entry.path("supported").deepCopy());
    result.set("accepted", entry.path("accepted").deepCopy());
    result.put("source", entry.path("source").asText());
    return result.put("message", "新作业按受支持版本筛选；已弃用但仍接受的版本仅供存量作业参考");
  }

  /** 按 Deployment 的完整选择器查询 Pod，不请求 Secret 或保存运行状态。 */
  public ObjectNode inspect() {
    var result =
        mapper
            .createObjectNode()
            .put("namespace", namespace)
            .put("deployment", deployment)
            .put("queriedAt", Instant.now().toString());
    var dep =
        client.call(
            "GET", "/apis/apps/v1/namespaces/" + namespace + "/deployments/" + deployment, null);
    if (dep.isMissingNode()) {
      result.put("found", false).set("compatibility", compatibility(""));
      return result;
    }
    result.put("found", true);
    var containers = dep.path("spec").path("template").path("spec").path("containers");
    String image = "";
    for (var item : containers)
      if (container.equals(item.path("name").asText())) image = item.path("image").asText();
    if (image.isEmpty() && containers.size() == 1) image = containers.get(0).path("image").asText();
    result
        .put("image", image)
        .put("replicas", dep.path("spec").path("replicas").asInt(1))
        .put("readyReplicas", dep.path("status").path("readyReplicas").asInt())
        .put("observedGeneration", dep.path("status").path("observedGeneration").asLong())
        .put("generation", dep.path("metadata").path("generation").asLong());
    result.set("compatibility", compatibility(image));
    List<String> selectors = new ArrayList<>();
    var selector = dep.path("spec").path("selector");
    selector
        .path("matchLabels")
        .fields()
        .forEachRemaining(e -> selectors.add(e.getKey() + "=" + e.getValue().asText()));
    for (var expr : selector.path("matchExpressions")) {
      String key = expr.path("key").asText(), op = expr.path("operator").asText();
      List<String> values = new ArrayList<>();
      expr.path("values").forEach(v -> values.add(v.asText()));
      switch (op) {
        case "In" -> selectors.add(key + " in (" + String.join(",", values) + ")");
        case "NotIn" -> selectors.add(key + " notin (" + String.join(",", values) + ")");
        case "Exists" -> selectors.add(key);
        case "DoesNotExist" -> selectors.add("!" + key);
        default -> throw new IllegalStateException("Invalid deployment selector");
      }
    }
    var rows = result.putArray("pods");
    if (selectors.isEmpty()) return result.put("podMessage", "Deployment 无选择器，未查询 Pod");
    JsonNode pods;
    try {
      pods =
          client.call(
              "GET",
              "/api/v1/namespaces/"
                  + namespace
                  + "/pods?labelSelector="
                  + URLEncoder.encode(String.join(",", selectors), StandardCharsets.UTF_8),
              null);
    } catch (com.flinkweb.exception.common.BusinessException ex) {
      return result.put("podMessage", ex.getMessage());
    }
    for (var pod : pods.path("items")) {
      boolean ready = false;
      for (var condition : pod.path("status").path("conditions"))
        if ("Ready".equals(condition.path("type").asText()))
          ready = "True".equals(condition.path("status").asText());
      var row =
          rows.addObject()
              .put("name", pod.path("metadata").path("name").asText())
              .put("phase", pod.path("status").path("phase").asText("Unknown"))
              .put("ready", ready)
              .put("deleting", pod.path("metadata").hasNonNull("deletionTimestamp"));
      var statuses = row.putArray("containers");
      for (var status : pod.path("status").path("containerStatuses")) {
        String state =
            status.path("state").has("running")
                ? "Running"
                : status.path("state").has("waiting") ? "Waiting" : "Terminated";
        statuses
            .addObject()
            .put("name", status.path("name").asText())
            .put("ready", status.path("ready").asBoolean())
            .put("restarts", status.path("restartCount").asInt())
            .put("state", state)
            .put("reason", status.path("state").path(state.toLowerCase()).path("reason").asText());
      }
    }
    return result;
  }
}
