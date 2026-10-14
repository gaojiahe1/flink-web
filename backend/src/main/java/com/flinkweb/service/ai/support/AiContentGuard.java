package com.flinkweb.service.ai.support;

import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 模型上下文和返回值的独立脱敏边界，不信任 SQL、注释、错误和日志中的指令。 */
@Component
public class AiContentGuard {
  private static final Pattern CREDENTIAL = Pattern.compile(
      "(?i)(['\"]?(?:password|passwd|secret(?:[_-]?key)?|api[_-]?key|access[_-]?key|authorization|token)['\"]?\\s*[:=]\\s*)(['\"]?)([^\\s,;\\n]+)");
  private final RecordRepository records;
  private final SecretCipher cipher;
  /** 注入密钥存储，仅作匹配，不把密钥列表传递给模型。 */
  public AiContentGuard(RecordRepository records,SecretCipher cipher) {this.records=records;this.cipher=cipher;}
  /** 遮蔽登记密钥、常见赋值、Bearer 和私钥内容。 */
  public String redact(String text) {
    String value=text;
    for(var config:records.list("configs")) if("secret".equals(config.path("kind").asText())) {
      String key=cipher.decrypt(config.path("id").asText(),config.path("encryptedValue").asText());
      if(!key.isEmpty()) value=value.replace(key,"[REDACTED]");
    }
    for(var model:records.list("ai_models")) if(model.has("encryptedKey")) {
      String key=cipher.decrypt("ai-model:"+model.path("id").asText(),model.path("encryptedKey").asText());
      if(!key.isEmpty()) value=value.replace(key,"[REDACTED]");
    }
    var matcher=CREDENTIAL.matcher(value);var buffer=new StringBuffer();
    while(matcher.find()) {
      String token=matcher.group(3);
      boolean reference=token.startsWith("{{") || token.startsWith("${") || token.startsWith("[REDACTED]");
      matcher.appendReplacement(buffer,java.util.regex.Matcher.quoteReplacement(reference?matcher.group():matcher.group(1)+"[REDACTED]"));
    }
    matcher.appendTail(buffer);
    return buffer.toString().replaceAll("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+","Bearer [REDACTED]")
        .replaceAll("(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----","[REDACTED PRIVATE KEY]")
        .replaceAll("(?i)(://[^/\\s:@]+:)[^@/\\s]+@","$1[REDACTED]@");
  }
  /** SQL 和需求含明文密钥时直接拒绝，不用静默修改后的 SQL 继续生成。 */
  public void requireSafe(String text) {
    if(!redact(text).equals(text)) throw new BusinessException(HttpStatus.BAD_REQUEST,"AI_SENSITIVE_CONTENT","内容含疑似明文凭据，请改用平台密钥引用后重试");
  }

  /** 按叶子字符串脱敏，避免改变 JSON 引号而破坏诊断上下文结构。 */
  public JsonNode sanitize(JsonNode value) {
    if(value.isTextual()) return TextNode.valueOf(redact(value.asText()));
    JsonNode copy=value.deepCopy();
    if(copy instanceof ObjectNode object) {
      var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);
      for(String name:names) object.set(name,sanitize(object.get(name)));
    } else if(copy instanceof ArrayNode array) for(int index=0;index<array.size();index++) array.set(index,sanitize(array.get(index)));
    return copy;
  }
}
