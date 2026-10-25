package com.flinkweb.service.job.runtime.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 按用户请求读取作业事件和受限日志；校验 Pod 所有者链，不提供自动跟随。 */
@Service
public class DiagnosticServiceImpl implements DiagnosticService {
  private final KubernetesClient client;
  private final JobService jobs;
  private final JobOwnershipService ownership;
  private final AccessService access;
  private final ObjectMapper mapper;
  private final RecordRepository records;
  private final SecretCipher cipher;
  private final com.flinkweb.service.session.SessionClusterService sessions;

  /** 注入集群、作业身份和脱敏依赖。 */
  public DiagnosticServiceImpl(
      KubernetesClient client,
      JobService jobs,
      JobOwnershipService ownership,
      AccessService access,
      ObjectMapper mapper,
      RecordRepository records,
      SecretCipher cipher,
      com.flinkweb.service.session.SessionClusterService sessions) {
    this.client = client;
    this.jobs = jobs;
    this.ownership = ownership;
    this.access = access;
    this.mapper = mapper;
    this.records = records;
    this.cipher = cipher;
    this.sessions = sessions;
  }

  /** 手动查询当前作业事件与 Pod 概览，不返回容器环境变量。 */
  public ObjectNode inspect(String id) {
    var job = jobs.get(id);
    var deployment = client.require(job.deploymentName());
    ownership.assertManaged(deployment, id);
    return inspectResource(deployment);
  }

  /** 共享 Session 诊断不需要虚构作业草稿，资源身份由 Session 服务核验。 */
  public ObjectNode inspectSession(String id) {
    return inspectResource(sessions.observedResource(id));
  }

  /** 对已核验工作负载复用相同的 Pod、容器和事件投影。 */
  private ObjectNode inspectResource(JsonNode deployment) {
    String uid = deployment.path("metadata").path("uid").asText();
    var result =
        mapper
            .createObjectNode()
            .put("queriedAt", java.time.Instant.now().toString())
            .put("error", redact(deployment.path("status").path("error").asText()));
    var pods = result.putArray("pods");
    for (JsonNode pod :
        client
            .call(
                "GET",
                client.core()
                    + "/pods?labelSelector="
                    + encode("app=" + deployment.at("/metadata/name").asText()),
                null)
            .path("items")) {
      if (!belongs(pod, uid, 0, new HashSet<>())) continue;
      var row =
          pods.addObject()
              .put("name", pod.path("metadata").path("name").asText())
              .put("phase", pod.path("status").path("phase").asText())
              .put("node", pod.path("spec").path("nodeName").asText())
              .put("createdAt", pod.path("metadata").path("creationTimestamp").asText())
              .put("reason", pod.path("status").path("reason").asText())
              .put("message", redact(pod.path("status").path("message").asText()));
      var conditions = row.putArray("conditions");
      for (JsonNode condition : pod.path("status").path("conditions"))
        conditions
            .addObject()
            .put("type", condition.path("type").asText())
            .put("status", condition.path("status").asText())
            .put("reason", condition.path("reason").asText())
            .put("message", redact(condition.path("message").asText()));
      var containers = row.putArray("containers");
      for (String type : List.of("initContainers", "containers"))
        for (JsonNode container : pod.path("spec").path(type))
          containers.add(container.path("name").asText());
      var states = row.putArray("states");
      for (String type : List.of("initContainerStatuses", "containerStatuses"))
        for (JsonNode state : pod.path("status").path(type)) {
          var node =
              states
                  .addObject()
                  .put("name", state.path("name").asText())
                  .put("restarts", state.path("restartCount").asInt())
                  .put("init", type.equals("initContainerStatuses"))
                  .put("ready", state.path("ready").asBoolean())
                  .put(
                      "state",
                      state.path("state").has("running")
                          ? "running"
                          : state.path("state").has("waiting")
                              ? "waiting"
                              : state.path("state").has("terminated") ? "terminated" : "unknown");
          if (state.path("state").has("terminated"))
            node.put("exitCode", state.path("state").path("terminated").path("exitCode").asInt());
          for (String phase : List.of("running", "terminated"))
            if (state.path("state").has(phase)) {
              node.put("startedAt", state.at("/state/" + phase + "/startedAt").asText());
              if (phase.equals("terminated"))
                node.put("finishedAt", state.at("/state/terminated/finishedAt").asText());
            }
          var previous = state.at("/lastState/terminated");
          if (previous.isObject())
            node.set(
                "lastTermination",
                mapper
                    .createObjectNode()
                    .put("exitCode", previous.path("exitCode").asInt())
                    .put("reason", previous.path("reason").asText())
                    .put("startedAt", previous.path("startedAt").asText())
                    .put("finishedAt", previous.path("finishedAt").asText()));
          for (String phase : List.of("waiting", "terminated"))
            if (state.path("state").has(phase)) {
              node.put("reason", state.path("state").path(phase).path("reason").asText());
              node.put("message", redact(state.path("state").path(phase).path("message").asText()));
            }
        }
    }
    var events = result.putArray("events");
    addEvents(events, uid);
    for (JsonNode pod : pods) {
      var resource = client.call("GET", client.core() + "/pods/" + pod.path("name").asText(), null);
      if (!resource.isMissingNode() && belongs(resource, uid, 0, new HashSet<>()))
        addEvents(events, resource.path("metadata").path("uid").asText());
    }
    return result;
  }

  /** Pod配置取证复用UID所有者链，不接受仅标签相同的外部Pod。 */
  public ObjectNode pod(String id, String podName) {
    if (!podName.matches("[a-z0-9][a-z0-9.-]{0,252}")) fail("Pod名称无效");
    var job = jobs.get(id);
    var deployment = client.require(job.deploymentName());
    ownership.assertManaged(deployment, id);
    var pod = client.call("GET", client.core() + "/pods/" + podName, null);
    if (pod.isMissingNode()
        || !belongs(pod, deployment.at("/metadata/uid").asText(), 0, new HashSet<>()))
      fail("Pod不属于该作业");
    return (ObjectNode) pod;
  }

  /** Flink REST异常与日志也必须遮蔽当前工作负载及其Pod引用的Kubernetes密钥。 */
  public JsonNode redactEvidence(String id, JsonNode evidence) {
    var job = jobs.get(id);
    var deployment = client.require(job.deploymentName());
    ownership.assertManaged(deployment, id);
    return redactResourceEvidence(deployment, evidence);
  }

  /** Session 逐次校验 UID 后共用日志密钥遮蔽。 */
  public JsonNode redactSessionEvidence(String id, JsonNode evidence) {
    return redactResourceEvidence(sessions.observedResource(id), evidence);
  }

  /** 收集当前资源及所属 Pod 的密钥引用，精确遮蔽而不返回原值。 */
  private JsonNode redactResourceEvidence(JsonNode deployment, JsonNode evidence) {
    var names = new HashSet<String>();
    collectSecretNames(deployment.path("spec"), names);
    var pods =
        client
            .call(
                "GET",
                client.core()
                    + "/pods?labelSelector="
                    + encode("app=" + deployment.at("/metadata/name").asText()),
                null)
            .path("items");
    if (pods.size() > 16) fail("关联Pod过多，无法在本轮预算内核对日志密钥");
    for (var pod : pods)
      if (belongs(pod, deployment.at("/metadata/uid").asText(), 0, new HashSet<>()))
        collectSecretNames(pod.path("spec"), names);
    if (names.size() > 16) fail("引用Secret过多，请通过运维日志平台查看");
    var secrets = new ArrayList<String>();
    for (String name : names) {
      if (!name.matches("[a-z0-9][a-z0-9.-]{0,252}")) fail("Secret引用名称无效");
      var secret = client.call("GET", client.core() + "/secrets/" + name, null);
      if (!secret.path("data").isObject()) fail("无法核对日志脱敏所需Secret，不能返回原始证据");
      for (var value : secret.path("data")) {
        String text =
            new String(Base64.getDecoder().decode(value.asText()), StandardCharsets.UTF_8);
        if (!text.isEmpty()) secrets.add(text);
      }
    }
    return maskEvidence(evidence, secrets);
  }

  /** 对字符串叶子做精确替换，不通过序列化字符串替换破坏JSON结构。 */
  private JsonNode maskEvidence(JsonNode evidence, List<String> secrets) {
    if (evidence.isTextual()) {
      String text = evidence.asText();
      for (String secret : secrets) text = text.replace(secret, "[REDACTED]");
      return com.fasterxml.jackson.databind.node.TextNode.valueOf(text);
    }
    var copy = evidence.deepCopy();
    if (copy instanceof ObjectNode object) {
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) object.set(name, maskEvidence(object.get(name), secrets));
    } else if (copy instanceof com.fasterxml.jackson.databind.node.ArrayNode array)
      for (int i = 0; i < array.size(); i++) array.set(i, maskEvidence(array.get(i), secrets));
    return copy;
  }

  /** 获取最多 10000 行，保留最新 4 MiB 日志，Pod 与容器必须属于该作业。 */
  public ObjectNode log(String id, String podName, String container, int lines, boolean previous) {
    access.require(id, "operate");
    if (!podName.matches("[a-z0-9][a-z0-9.-]{0,252}")
        || !container.matches("[a-z0-9][a-z0-9-]{0,62}")) fail("Pod 或容器名称无效");
    var job = jobs.get(id);
    var deployment = client.require(job.deploymentName());
    ownership.assertManaged(deployment, id);
    return logResource(id, "JOB_LOG_READ", deployment, podName, container, lines, previous);
  }

  /** Session 日志仅由控制器允许管理员及运维读取，仍校验 Pod 所有者链。 */
  public ObjectNode sessionLog(
      String id, String podName, String container, int lines, boolean previous) {
    return logResource(
        id, "SESSION_LOG_READ", sessions.observedResource(id), podName, container, lines, previous);
  }

  /** 核对容器归属后读取受限日志并审计，不返回 Secret 值。 */
  private ObjectNode logResource(
      String id,
      String action,
      JsonNode deployment,
      String podName,
      String container,
      int lines,
      boolean previous) {
    if (!podName.matches("[a-z0-9][a-z0-9.-]{0,252}")
        || !container.matches("[a-z0-9][a-z0-9-]{0,62}")) fail("Pod 或容器名称无效");
    var pod = client.call("GET", client.core() + "/pods/" + podName, null);
    if (pod.isMissingNode()
        || !belongs(pod, deployment.path("metadata").path("uid").asText(), 0, new HashSet<>()))
      fail("Pod 不属于该作业");
    boolean found = false;
    for (String field : List.of("containers", "initContainers"))
      for (JsonNode c : pod.path("spec").path(field))
        if (container.equals(c.path("name").asText())) found = true;
    if (!found) fail("容器不存在");
    int limit = Math.max(1, Math.min(lines, 10000));
    String text =
        client.text(
            client.core()
                + "/pods/"
                + podName
                + "/log?container="
                + encode(container)
                + "&tailLines="
                + limit
                + "&timestamps=true&previous="
                + previous,
            4 * 1024 * 1024);
    records.save(
        "audit",
        UUID.randomUUID().toString(),
        mapper
            .createObjectNode()
            .put("action", action)
            .put("target", id)
            .put("actor", access.actor())
            .put("outcome", "SUCCESS"),
        0);
    return mapper
        .createObjectNode()
        .put("text", redactPodSecrets(redact(text), pod))
        .put("queriedAt", java.time.Instant.now().toString())
        .put("limitBytes", 4 * 1024 * 1024)
        .put("lines", limit);
  }

  /** 遮蔽本 Pod 实际引用的 Kubernetes Secret 值，读取失败时不返回原始日志。 */
  private String redactPodSecrets(String text, JsonNode pod) {
    Set<String> names = new HashSet<>();
    collectSecretNames(pod.path("spec"), names);
    if (names.size() > 16) fail("该 Pod 引用的 Secret 过多，请通过运维日志平台查看");
    for (String name : names) {
      if (!name.matches("[a-z0-9][a-z0-9.-]{0,252}")) fail("Secret 引用名称无效");
      var secret = client.call("GET", client.core() + "/secrets/" + name, null);
      if (!secret.path("data").isObject()) fail("无法读取日志脱敏所需的 Secret，请检查 RBAC");
      for (JsonNode value : secret.path("data")) {
        String decoded =
            new String(Base64.getDecoder().decode(value.asText()), StandardCharsets.UTF_8);
        if (!decoded.isEmpty()) text = text.replace(decoded, "******");
      }
    }
    return text;
  }

  /** 收集环境变量、整组注入和卷中 Secret 的名称，不输出密钥内容。 */
  private void collectSecretNames(JsonNode node, Set<String> names) {
    if (node.isObject()) {
      for (String key : List.of("secretKeyRef", "secretRef"))
        if (node.path(key).has("name")) names.add(node.path(key).path("name").asText());
      if (node.has("secretName")) names.add(node.path("secretName").asText());
      if (node.path("secret").has("name")) names.add(node.path("secret").path("name").asText());
    }
    if (node.isContainerNode()) for (JsonNode child : node) collectSecretNames(child, names);
  }

  /** 校验 Pod→ReplicaSet→Deployment→FlinkDeployment 的 UID 链，拒绝只伪造标签的资源。 */
  private boolean belongs(JsonNode node, String uid, int depth, Set<String> visited) {
    if (depth > 4 || uid.isBlank()) return false;
    for (JsonNode owner : node.path("metadata").path("ownerReferences")) {
      String ownerUid = owner.path("uid").asText();
      if (uid.equals(ownerUid) && "FlinkDeployment".equals(owner.path("kind").asText()))
        return true;
      String plural =
          switch (owner.path("kind").asText()) {
            case "ReplicaSet" -> "replicasets";
            case "Deployment" -> "deployments";
            case "StatefulSet" -> "statefulsets";
            default -> "";
          };
      String name = owner.path("name").asText();
      if (plural.isEmpty() || !name.matches("[a-z0-9][a-z0-9.-]{0,252}") || !visited.add(ownerUid))
        continue;
      String path = client.core().replace("/api/v1/", "/apis/apps/v1/") + "/" + plural + "/" + name;
      var parent = client.call("GET", path, null);
      if (ownerUid.equals(parent.path("metadata").path("uid").asText())
          && belongs(parent, uid, depth + 1, visited)) return true;
    }
    return false;
  }

  /** 只返回 UID 对应事件的必要字段，避免全 Namespace 事件泄露。 */
  private void addEvents(com.fasterxml.jackson.databind.node.ArrayNode output, String uid) {
    if (uid.isBlank()) return;
    for (JsonNode event :
        client
            .call(
                "GET",
                client.core()
                    + "/events?fieldSelector="
                    + encode("involvedObject.uid=" + uid)
                    + "&limit=100",
                null)
            .path("items"))
      output
          .addObject()
          .put("time", event.path("lastTimestamp").asText(event.path("eventTime").asText()))
          .put("type", event.path("type").asText())
          .put("reason", event.path("reason").asText())
          .put("message", redact(event.path("message").asText()));
  }

  /** 隐藏平台已知密钥和常见凭据模式；原始日志不写入数据库。 */
  private String redact(String value) {
    String text = value;
    for (var config : records.list("configs"))
      if ("secret".equals(config.path("kind").asText())) {
        String secret =
            cipher.decrypt(config.path("id").asText(), config.path("encryptedValue").asText());
        if (!secret.isEmpty()) text = text.replace(secret, "******");
      }
    return text.replaceAll(
            "(?i)((?:password|passwd|secret|token|access[_-]?key|authorization)\\s*[:=]\\s*)([^\\s,;]+)",
            "$1******")
        .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/-]+", "$1******")
        .replaceAll("(://)[^\\s/@:]+:[^\\s/@]+@", "$1******@");
  }

  /** 编码查询参数。 */
  private String encode(String text) {
    return URLEncoder.encode(text, StandardCharsets.UTF_8);
  }

  /** 输出身份或参数错误。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_DIAGNOSTIC", message);
  }
}
