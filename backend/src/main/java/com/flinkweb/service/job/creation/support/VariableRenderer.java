package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.config.platform.PlatformProperties;
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
  private final PlatformProperties properties;

  /** 注入配置记录、加密器与 JSON 映射器，并读取目标 Namespace。 */
  public VariableRenderer(
      RecordRepository store,
      SecretCipher cipher,
      ObjectMapper mapper,
      PlatformProperties properties,
      com.flinkweb.service.artifact.support.ArtifactPodBuilder artifacts,
      S3PluginPodBuilder s3Plugin) {
    this.s3Plugin = s3Plugin;
    this.store = store;
    this.artifacts = artifacts;
    this.cipher = cipher;
    this.mapper = mapper;
    this.properties = properties;
  }

  /** 复制部署资源并在请求内展开配置引用，不生成 Kubernetes Secret。 */
  public RenderedDeployment render(ObjectNode source) {
    List<ObjectNode> secrets = new ArrayList<>();
    ObjectNode deployment = source.deepCopy();
    artifacts.prepare(deployment.withObject("/spec"));
    s3Plugin.apply(deployment.withObject("/spec"));
    bindS3Credentials(deployment);
    ObjectNode rendered = (ObjectNode) walk(deployment, secrets, "");
    return new RenderedDeployment(rendered, secrets);
  }

  /** 将 S3 密钥变量映射为 Flink 主容器凭据，预览和历史另行恢复占位符。 */
  private void bindS3Credentials(ObjectNode deployment) {
    JsonNode config = deployment.at("/spec/flinkConfiguration");
    String access = config.path("s3.access-key").asText();
    String secret = config.path("s3.secret-key").asText();
    boolean referenced = access.startsWith("{{secret:") || secret.startsWith("{{secret:");
    if (!referenced) return;
    for (String value : List.of(access, secret)) {
      var match = TOKEN.matcher(value);
      if (!match.matches() || !match.group(1).equals("secret"))
        fail("s3.access-key 和 s3.secret-key 必须同时引用配置管理中的密钥");
      if (store.find("configs", match.group(2)) == null) fail("请先在配置管理中配置 S3 密钥：" + match.group(2));
    }
    ObjectNode spec = deployment.withObject("/spec");
    injectS3Environment(spec.withObject("/podTemplate"), access, secret);
    ((ObjectNode) config).remove(List.of("s3.access-key", "s3.secret-key"));
  }

  /** 合并主容器的 AWS 凭据变量，保留其他环境变量、边车和模板字段。 */
  private void injectS3Environment(ObjectNode template, String access, String secret) {
    ArrayNode containers = template.withObject("/spec").withArray("containers");
    ObjectNode main = null;
    for (JsonNode container : containers)
      if ("flink-main-container".equals(container.path("name").asText()))
        main = (ObjectNode) container;
    if (main == null) main = containers.addObject().put("name", "flink-main-container");
    ArrayNode env = main.withArray("env");
    for (int i = env.size() - 1; i >= 0; i--)
      if (Set.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN")
          .contains(env.get(i).path("name").asText())) env.remove(i);
    env.addObject().put("name", "AWS_ACCESS_KEY_ID").put("value", access);
    env.addObject().put("name", "AWS_SECRET_ACCESS_KEY").put("value", secret);
  }

  /** 遍历 YAML 节点，保留普通变量类型，并在环境变量中展开加密值。 */
  private JsonNode walk(JsonNode node, List<ObjectNode> secrets, String parent) {
    if (node.isObject()) {
      ObjectNode object = (ObjectNode) node;
      if (parent.equals("env") && object.has("name") && object.path("value").isTextual()) {
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
      for (String name : names) object.set(name, walk(object.get(name), secrets, name));
      return object;
    }
    if (node.isArray()) {
      ArrayNode result = mapper.createArrayNode();
      for (JsonNode child : node) result.add(walk(child, secrets, parent));
      return result;
    }
    if (node.isTextual()) {
      String value = node.asText();
      var match = TOKEN.matcher(value);
      if (match.matches()) {
        if (match.group(1).equals("secret"))
          fail("密钥变量只支持 PodTemplate containers/initContainers 的 env.value，请使用 Secret 引用");
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
    ObjectNode result = resource.deepCopy();
    Map<String, String> values = new LinkedHashMap<>();
    for (var config : store.list("configs"))
      if ("secret".equals(config.path("kind").asText())) {
        String value =
            cipher.decrypt(config.path("id").asText(), config.path("encryptedValue").asText());
        if (!value.isEmpty())
          values.putIfAbsent(value, "{{secret:" + config.path("id").asText() + "}}");
      }
    return (ObjectNode) mask(result, values);
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
