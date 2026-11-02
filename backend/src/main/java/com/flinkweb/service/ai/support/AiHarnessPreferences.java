package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.Set;

/** 用户明确维护的私有跨会话偏好；不存储权限、批准或运行事实。 */
@Component
public class AiHarnessPreferences {
  private final RecordRepository records;
  private final AccessService access;
  private final ObjectMapper mapper;
  private final AiContentGuard guard;
  /** 复用元数据乐观锁及敏感内容检查。 */
  public AiHarnessPreferences(RecordRepository records, AccessService access, ObjectMapper mapper, AiContentGuard guard) {
    this.records=records;this.access=access;this.mapper=mapper;this.guard=guard;
  }
  /** 仅使用当前登录身份定位偏好，缺失返回空值。 */
  public ObjectNode get() {
    var row=records.find("ai_harness_preferences",access.actor());
    return row==null?mapper.createObjectNode().put("revision",0).set("values",mapper.createObjectNode()):row;
  }
  /** 显式设置或清空偏好，不从模型回复自动学习。 */
  public ObjectNode save(ObjectNode input) {
    var values=input.path("values");
    if(!values.isObject()||values.size()>5)invalid();
    values.fields().forEachRemaining(f->{
      if(!Set.of("responseStyle","defaultCatalogId","defaultSessionId","sqlStyle","logLines").contains(f.getKey())||!f.getValue().isTextual()||f.getValue().asText().length()>300)invalid();
      if(f.getKey().equals("logLines")&&!f.getValue().asText().matches("[1-9][0-9]{0,3}|10000"))invalid();
    });
    guard.requireSafe(values.toString());
    var row=mapper.createObjectNode().put("owner",access.actor()).put("source","USER_EXPLICIT");row.set("values",values.deepCopy());
    return records.save("ai_harness_preferences",access.actor(),row,input.path("revision").asLong());
  }
  /** 不合规字段不会被持久化成隐含授权。 */
  private void invalid(){throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_INVALID_PREFERENCE","偏好格式或字段无效");}
}
