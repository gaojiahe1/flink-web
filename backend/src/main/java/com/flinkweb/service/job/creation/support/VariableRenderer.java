package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.dto.job.creation.RenderedDeployment;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 解析配置引用，在提交时展开加密密钥，保留用户显式登记的 Secret 引用。 */
@Service
public class VariableRenderer {
  private static final Pattern TOKEN =
      Pattern.compile("^\\{\\{(config|secret):([a-z0-9][a-z0-9_.-]{0,100})}}$");
  private final RecordRepository store;
  private final S3PluginPodBuilder s3Plugin;
  private final com.flinkweb.service.artifact.support.ArtifactPodBuilder artifacts;
  private final SecretCipher cipher;
  private final ObjectMapper mapper;
  private final KubernetesClient kubernetes;

  /** 注入配置记录、加密器与 JSON 映射器，并读取目标 Namespace。 */
  public VariableRenderer(
      RecordRepository store,
      SecretCipher cipher,
      ObjectMapper mapper,
      com.flinkweb.service.artifact.support.ArtifactPodBuilder artifacts,
      S3PluginPodBuilder s3Plugin,
      KubernetesClient kubernetes) {
    this.kubernetes = kubernetes;
    this.s3Plugin = s3Plugin;
    this.store = store;
    this.artifacts = artifacts;
    this.cipher = cipher;
    this.mapper = mapper;
  }

  /** 复制部署资源并在请求内展开配置引用，不生成 Kubernetes Secret。 */
  public RenderedDeployment render(ObjectNode source) {
    List<ObjectNode> secrets = new ArrayList<>();
    ObjectNode deployment = source.deepCopy();
    artifacts.prepare(deployment.withObject("/spec"));
    s3Plugin.apply(deployment.withObject("/spec"));
    bindS3Credentials(deployment);
    ObjectNode rendered = (ObjectNode) walk(deployment, secrets, "", new HashMap<>());
    return new RenderedDeployment(rendered, secrets);
  }

  /** 校验 S3 密钥引用，并移除共享及角色主容器旧的 AK/SK 环境变量。 */
  private void bindS3Credentials(ObjectNode deployment) {
    JsonNode config = deployment.at("/spec/flinkConfiguration");
    String access = config.path("s3.access-key").asText();
    String secret = config.path("s3.secret-key").asText();
    boolean referenced = access.startsWith("{{secret:") || secret.startsWith("{{secret:");
    if (referenced)
      for (String value : List.of(access, secret)) {
        var match = TOKEN.matcher(value);
        if (!match.matches() || !match.group(1).equals("secret"))
          fail("s3.access-key 和 s3.secret-key 必须同时引用配置管理中的密钥");
        if (store.find("configs", match.group(2)) == null)
          fail("请先在配置管理中配置 S3 密钥：" + match.group(2));
      }
    if (access.isBlank() || secret.isBlank()) return;
    ObjectNode spec = deployment.withObject("/spec");
    for (String role : List.of("", "jobManager", "taskManager")) {
      JsonNode pod = (role.isEmpty() ? spec : spec.path(role)).path("podTemplate").path("spec");
      for (JsonNode container : pod.path("containers")) {
        if (!"flink-main-container".equals(container.path("name").asText())) continue;
        if (container.path("env") instanceof ArrayNode env)
          for (int i = env.size() - 1; i >= 0; i--)
            if (Set.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY")
                .contains(env.get(i).path("name").asText())) env.remove(i);
      }
    }
  }

  /** 将 Flink S3 配置中的密钥引用展开为标量；已有 Secret 按请求读取且不缓存明文。 */
  private JsonNode s3Credential(String id, Map<String, JsonNode> secretCache) {
    var config = store.require("configs", id);
    if ("secret".equals(config.path("kind").asText()))
      return TextNode.valueOf(cipher.decrypt(id, config.path("encryptedValue").asText()));
    if (!"secretRef".equals(config.path("kind").asText())) {
      fail("S3 密钥引用必须指向密钥配置");
      return null;
    }
    String name = config.path("secretName").asText(), key = config.path("secretKey").asText();
    var secret =
        secretCache.computeIfAbsent(
            name, n -> kubernetes.call("GET", kubernetes.core() + "/secrets/" + n, null));
    if (secret == null || !secret.path("data").path(key).isTextual()) {
      fail("无法读取 S3 凭据的 Kubernetes Secret 或指定键：" + name + "/" + key);
      return null;
    }
    try {
      String value =
          new String(
              Base64.getDecoder().decode(secret.path("data").path(key).asText()),
              java.nio.charset.StandardCharsets.UTF_8);
      if (value.isEmpty()) fail("S3 凭据的 Kubernetes Secret 值不能为空");
      return TextNode.valueOf(value);
    } catch (IllegalArgumentException ex) {
      fail("S3 凭据的 Kubernetes Secret 值不是合法 Base64");
      return null;
    }
  }

  /** 按完整节点路径遍历 YAML，仅在容器环境或 Flink S3 凭据位置展开密钥。 */
  private JsonNode walk(
      JsonNode node, List<ObjectNode> secrets, String parent, Map<String, JsonNode> secretCache) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      if (parent.endsWith("/env") && object.has("name") && object.path("value").isTextual()) {
        var match = TOKEN.matcher(object.path("value").asText());
        if (match.matches() && match.group(1).equals("secret")) {
          if (store.find("configs", match.group(2)) == null) fail("请先在配置管理中配置密钥：" + match.group(2));
          var config = store.require("configs", match.group(2));
          String name, key;
          if (config.path("kind").asText().equals("secretRef")) {
            name = config.path("secretName").asText();
            key = config.path("secretKey").asText();
          } else if (config.path("kind").asText().equals("secret")) {
            object.remove("valueFrom");
            object.put(
                "value", cipher.decrypt(match.group(2), config.path("encryptedValue").asText()));
            return object;
          } else {
            fail("secret 引用必须指向密钥配置");
            return null;
          }
          object.remove("value");
          object.putObject("valueFrom").putObject("secretKeyRef").put("name", name).put("key", key);
          return object;
        }
      }
      var names = new ArrayList<String>();
      object.fieldNames().forEachRemaining(names::add);
      for (String name : names) {
        var match = TOKEN.matcher(object.path(name).asText());
        if (parent.equals("/spec/flinkConfiguration")
            && Set.of("s3.access-key", "s3.secret-key").contains(name)
            && match.matches()
            && match.group(1).equals("secret"))
          object.set(name, s3Credential(match.group(2), secretCache));
        else object.set(name, walk(object.get(name), secrets, parent + "/" + name, secretCache));
      }
      return object;
    }
    if (node.isArray()) {
      ArrayNode result = mapper.createArrayNode();
      for (JsonNode child : node) result.add(walk(child, secrets, parent, secretCache));
      return result;
    }
    if (node.isTextual()) {
      String value = node.asText();
      var match = TOKEN.matcher(value);
      if (match.matches()) {
        if (match.group(1).equals("secret"))
          fail("密钥变量仅支持容器 env.value 或 flinkConfiguration 的 s3.access-key / s3.secret-key");
        var config = store.require("configs", match.group(2));
        if (!"plain".equals(config.path("kind").asText())) fail("config 变量只能引用普通配置");
        return config.path("value").deepCopy();
      }
      if (value.contains("{{")) fail("变量必须独占一个 YAML 标量，并用引号包裹");
    }
    return node;
  }

  /** 本地预览恢复密钥占位符，避免展开后的实际值进入浏览器或被重新保存。 */
  public ObjectNode preview(ObjectNode resource) {
    return preview(resource, null);
  }

  /** 按源配置恢复 S3 密钥引用；无法对应的实际凭据统一隐藏。 */
  public ObjectNode preview(ObjectNode resource, JsonNode source) {
    ObjectNode result = resource.deepCopy();
    Map<String, String> values = new LinkedHashMap<>();
    for (var config : store.list("configs"))
      if ("secret".equals(config.path("kind").asText())) {
        String value =
            cipher.decrypt(config.path("id").asText(), config.path("encryptedValue").asText());
        if (!value.isEmpty())
          values.putIfAbsent(value, "{{secret:" + config.path("id").asText() + "}}");
      }
    result = (ObjectNode) mask(result, values);
    JsonNode config = result.at("/spec/flinkConfiguration");
    if (config.isObject())
      for (String key : List.of("s3.access-key", "s3.secret-key")) {
        if (!config.has(key)) continue;
        String reference =
            source == null
                ? config.path(key).asText()
                : source.at("/spec/flinkConfiguration").path(key).asText();
        var match = TOKEN.matcher(reference);
        ((ObjectNode) config)
            .put(key, match.matches() && match.group(1).equals("secret") ? reference : "******");
      }
    return result;
  }

  /** 递归替换实际密钥值，兼顾嵌入文本和纯标量引用。 */
  private JsonNode mask(JsonNode node, Map<String, String> values) {
    if (node.isObject()) {
      var names = new ArrayList<String>();
      node.fieldNames().forEachRemaining(names::add);
      for (String name : names) ((ObjectNode) node).set(name, mask(node.get(name), values));
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) ((ArrayNode) node).set(i, mask(node.get(i), values));
    } else if (node.isTextual()) {
      String text = node.asText();
      if (values.containsKey(text)) return TextNode.valueOf(values.get(text));
      for (String value : values.keySet()) text = text.replace(value, "******");
      return TextNode.valueOf(text);
    }
    return node;
  }

  /** 将本次渲染生成的 Secret 写入目标 Namespace，校验已有 Secret 的归属。 */
  public void materialize(RenderedDeployment rendered, KubernetesClient client) {
    for (ObjectNode secret : rendered.secrets()) {
      String path = client.core() + "/secrets",
          name = secret.path("metadata").path("name").asText();
      JsonNode old = client.call("GET", path + "/" + name, null);
      if (old.isMissingNode()) client.call("POST", path, secret);
      else if (!old.path("data").equals(secret.path("data"))
          || !old.path("metadata")
              .path("labels")
              .path("app.kubernetes.io/managed-by")
              .asText()
              .equals("flink-web"))
        throw new BusinessException(HttpStatus.CONFLICT, "SECRET_CONFLICT", "受管 Secret 已存在且内容不匹配");
    }
  }

  /** 抛出配置引用位置或类型不合法的业务异常。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_VARIABLE", message);
  }
}
