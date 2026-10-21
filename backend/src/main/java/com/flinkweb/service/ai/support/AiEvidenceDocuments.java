package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.service.job.creation.YamlService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;

/** 展示完整资源结构并遮蔽值，合法Secret引用保持可读，脱敏文档不作为可写候选。 */
@Component
public class AiEvidenceDocuments {
  private final YamlService yaml;
  private final AiContentGuard guard;

  /** 复用YAML序列化和请求局部密钥防护。 */
  public AiEvidenceDocuments(YamlService yaml,AiContentGuard guard) {this.yaml=yaml;this.guard=guard;}

  /** 去除托管字段及内嵌旧清单，完整保留spec、引用和状态；环境字面值统一遮蔽。 */
  public String yaml(JsonNode source) {
    return yaml.dump(resource(source));
  }

  /** 保留资源完整结构供分类投影复用，密钥与环境字面值不可随结构外发。 */
  public JsonNode resource(JsonNode source) {
    var copy=source.deepCopy();
    if(copy.path("metadata") instanceof ObjectNode metadata) {
      metadata.remove("managedFields");
      if(metadata.path("annotations") instanceof ObjectNode annotations)annotations.remove("kubectl.kubernetes.io/last-applied-configuration");
    }
    scrub(copy);
    return guard.sanitize(copy);
  }

  /** 展示解码后的部署SQL，凭据脱敏与其他文本保持相同规则。 */
  public String sql(JsonNode source) {
    var statements=new ArrayList<String>();var args=source.at("/spec/job/args");
    for(int i=0;i<args.size()-1;i++) if(args.get(i).asText().equals("--statement")) {
      try {statements.add(guard.redact(new String(Base64.getDecoder().decode(args.get(++i).asText()),StandardCharsets.UTF_8)));}
      catch(IllegalArgumentException ignored) {return "部署参数不是有效的SQL编码，请核对已发布配置";}
    }
    return String.join(";\n",statements);
  }

  /** 结构化识别敏感字段，避免仅按值脱敏遗漏展开的S3密钥。 */
  private void scrub(JsonNode node) {
    if(node instanceof ObjectNode object) {
      if(object.has("name") && object.has("value") && !object.path("value").asText().startsWith("{{"))object.put("value","[REDACTED ENV VALUE]");
      var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);
      for(String name:names) {
        var value=object.get(name);
        if(value.isTextual() && name.matches("(?i).*(password|passwd|secret[._-]key|access[._-]key|token|authorization|credential|private[._-]key|api[._-]key).*")) {
          if(!value.asText().startsWith("{{") && !value.asText().startsWith("${"))object.put(name,"[REDACTED]");
        } else scrub(value);
      }
      if(object.path("args").isArray()) {
        var args=(com.fasterxml.jackson.databind.node.ArrayNode)object.path("args");
        for(int i=0;i<args.size()-1;i++)if(args.get(i).asText().equals("--statement")) {
          try {String text=new String(Base64.getDecoder().decode(args.get(i+1).asText()),StandardCharsets.UTF_8);args.set(++i,com.fasterxml.jackson.databind.node.TextNode.valueOf(Base64.getEncoder().encodeToString(guard.redact(text).getBytes(StandardCharsets.UTF_8))));}
          catch(IllegalArgumentException ignored) {args.set(++i,com.fasterxml.jackson.databind.node.TextNode.valueOf("[UNREADABLE SQL]"));}
        }
      }
    } else if(node.isArray())for(var child:node)scrub(child);
  }
}
