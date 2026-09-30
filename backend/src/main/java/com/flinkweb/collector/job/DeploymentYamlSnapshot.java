package com.flinkweb.collector.job;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import java.util.*;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/** 将本次采集到的资源转换为只读 YAML，屏蔽凭据及不可安全回显的嵌入配置。 */
@Component
public class DeploymentYamlSnapshot {
  private final RecordRepository records;
  private final SecretCipher cipher;
  private final ObjectMapper mapper;

  /** 注入密钥库以替换平台已知密文的实际值，不额外访问 Kubernetes。 */
  public DeploymentYamlSnapshot(
      RecordRepository records, SecretCipher cipher, ObjectMapper mapper) {
    this.records = records;
    this.cipher = cipher;
    this.mapper = mapper;
  }

  /** 每批采集只解密一次已登记密钥；该列表仅存在于当前请求内。 */
  public List<String> secrets() {
    List<String> values = new ArrayList<>();
    for (var config : records.list("configs"))
      if ("secret".equals(config.path("kind").asText())) {
        String value =
            cipher.decrypt(config.path("id").asText(), config.path("encryptedValue").asText());
        if (!value.isEmpty()) values.add(value);
      }
    return values;
  }

  /** 输出脱敏快照，保留资源版本和状态；不得将此内容直接用于重新部署。 */
  public String render(JsonNode deployment, List<String> secrets) {
    ObjectNode source = ((ObjectNode) deployment).deepCopy();
    JsonNode status = source.remove("status");
    ObjectNode copy = (ObjectNode) clean(source, secrets, "");
    if (status != null) copy.set("status", status);
    var options = new org.yaml.snakeyaml.DumperOptions();
    options.setDefaultFlowStyle(org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(true);
    options.setSplitLines(false);
    return new Yaml(options).dump(mapper.convertValue(copy, Object.class));
  }

  /** 递归处理敏感字段，环境变量字面值、命令参数和序列化状态一律隐藏。 */
  private JsonNode clean(JsonNode node, List<String> secrets, String parent) {
    if (node.isObject()) {
      ObjectNode out = mapper.createObjectNode();
      node.fields()
          .forEachRemaining(
              e -> {
                String key = e.getKey();
                if (key.equals("managedFields")) return;
                boolean hidden =
                    parent.equals("annotations")
                        || key.matches(
                            "(?i).*(password|passwd|secret|access.key|token|credential|authorization).*")
                        || Set.of(
                                "args", "command", "lastReconciledSpec", "lastStableSpec", "error")
                            .contains(key)
                        || (key.equals("value") && node.has("name") && parent.equals("env"));
                out.set(
                    key, hidden ? TextNode.valueOf("******") : clean(e.getValue(), secrets, key));
              });
      return out;
    }
    if (node.isArray()) {
      var out = mapper.createArrayNode();
      node.forEach(n -> out.add(clean(n, secrets, parent)));
      return out;
    }
    if (node.isTextual()) {
      String value = node.asText();
      for (String secret : secrets) value = value.replace(secret, "******");
      value = value.replaceAll("(://)[^\\s/@:]+:[^\\s/@]+@", "$1******@");
      return TextNode.valueOf(value);
    }
    return node.deepCopy();
  }
}
