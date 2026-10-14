package com.flinkweb.service.ai.impl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.ai.AiModelClient;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.security.permission.SecretCipher;
import com.flinkweb.service.ai.AiModelService;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.permission.AccessService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 模型密钥独立加密，列表和任务摘要均不输出密文或明文。 */
@Service
public class AiModelServiceImpl implements AiModelService {
  private final RecordRepository records;
  private final SecretCipher cipher;
  private final AccessService access;
  private final AuditService audit;
  private final AiModelClient client;

  /** 注入模型存储、加密、权限及协议适配器。 */
  public AiModelServiceImpl(RecordRepository records, SecretCipher cipher, AccessService access,
      AuditService audit, AiModelClient client) {
    this.records=records; this.cipher=cipher; this.access=access; this.audit=audit; this.client=client;
  }

  /** 管理员专用页面展示配置，普通调用者只看启用模型和用途。 */
  public List<ObjectNode> list(boolean management) {
    if (management) admin();
    return records.list("ai_models").stream().filter(m -> management || m.path("enabled").asBoolean())
        .map(m -> visible(m, management)).toList();
  }

  /** 只允许管理员设置目的地址及密钥，更新空密钥保留原值。 */
  public ObjectNode save(String id, ObjectNode input) {
    admin();
    if (!id.matches("[a-z0-9][a-z0-9_.-]{0,63}")) fail("模型标识仅支持小写字母、数字、点、横线和下划线");
    String name=input.path("name").asText().trim(), model=input.path("model").asText().trim();
    if (name.isEmpty() || name.length()>80 || !model.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,199}")) fail("请填写模型名称和有效的实际模型标识");
    String base=input.path("baseUrl").asText().trim();
    if (base.length()>500) fail("模型地址过长");
    try { client.endpoint(base,input.path("privateNetwork").asBoolean()); }
    catch (IllegalArgumentException error) { fail(error.getMessage()); }
    int timeout=input.path("timeoutSeconds").asInt(90), tokens=input.path("maxTokens").asInt(4096);
    if (timeout<5 || timeout>180 || tokens<256 || tokens>8192) fail("超时应为5–180秒，输出上限应为256–8192 tokens");
    var node=input.objectNode().put("name",name).put("model",model).put("baseUrl",base)
        .put("enabled",input.path("enabled").asBoolean(true)).put("external",input.path("external").asBoolean(true))
        .put("privateNetwork",input.path("privateNetwork").asBoolean()).put("timeoutSeconds",timeout).put("maxTokens",tokens);
    var purposes=node.putArray("purposes");
    for (var purpose:input.path("purposes")) {
      if (!Set.of("sql","definition","diagnosis").contains(purpose.asText())) fail("模型用途无效");
      purposes.add(purpose.asText());
    }
    if (purposes.isEmpty() || purposes.size()>3) fail("至少选择一种模型用途");
    String key=input.path("apiKey").asText("");
    if (key.length()>8192 || key.contains("\n") || key.contains("\r")) fail("模型密钥格式无效");
    ObjectNode old=records.find("ai_models",id);
    if (!key.isEmpty()) node.put("encryptedKey",cipher.encrypt("ai-model:"+id,key));
    else if (!input.path("clearKey").asBoolean() && old!=null && old.has("encryptedKey")) node.set("encryptedKey",old.get("encryptedKey"));
    ObjectNode saved=records.save("ai_models",id,node,input.path("revision").asLong());
    audit.record("AI_MODEL_SAVE",id,"SUCCESS");
    return visible(saved,true);
  }

  /** 按修订删除，历史任务保留当时的模型版本摘要。 */
  public void delete(String id,long revision) { admin(); records.delete("ai_models",id,revision); audit.record("AI_MODEL_DELETE",id,"SUCCESS"); }

  /** 每次新任务读取最新启用状态和用途，不使用浏览器传入的端点。 */
  public ObjectNode require(String id,String purpose) {
    var model=records.require("ai_models",id);
    if (!model.path("enabled").asBoolean() || !java.util.stream.StreamSupport.stream(model.path("purposes").spliterator(),false).anyMatch(p->p.asText().equals(purpose)))
      throw new BusinessException(HttpStatus.BAD_REQUEST,"AI_MODEL_UNAVAILABLE","模型未启用或不支持当前用途");
    return model;
  }

  /** 解密使用模型专属 AAD，不能与普通配置互换。 */
  public String key(ObjectNode model) { return model.has("encryptedKey") ? cipher.decrypt("ai-model:"+model.path("id").asText(),model.path("encryptedKey").asText()) : ""; }

  /** 仅发送固定测试消息，不携带表结构、SQL 或日志。 */
  public ObjectNode test(String id) {
    admin(); var model=records.require("ai_models",id);
    try {
      client.complete(model,key(model),"Return only a JSON object: {\"ok\":true}","Connection test");
      audit.record("AI_MODEL_TEST",id,"SUCCESS"); return model.objectNode().put("ok",true).put("message","模型连接和 JSON 响应正常");
    } catch (Exception error) {
      if (error instanceof InterruptedException) Thread.currentThread().interrupt();
      audit.record("AI_MODEL_TEST",id,"FAILED");
      throw new BusinessException(HttpStatus.BAD_GATEWAY,"AI_MODEL_CONNECTION_FAILED","模型测试失败，请检查地址、密钥、模型标识及兼容协议");
    }
  }

  /** 剔除凭据，任务选择列表不暴露端点和网络授权。 */
  private ObjectNode visible(ObjectNode model,boolean management) {
    var copy=model.deepCopy();copy.put("hasKey",copy.has("encryptedKey"));copy.remove("encryptedKey");
    if (!management) copy.remove(List.of("baseUrl","privateNetwork","hasKey"));
    return copy;
  }
  /** 服务层也检查管理员，避免内部调用绕过控制器。 */
  private void admin() { if(!access.admin()) throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","仅管理员可管理模型服务"); }
  /** 返回不含配置值的字段错误。 */
  private void fail(String message) { throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_AI_MODEL",message); }
}
