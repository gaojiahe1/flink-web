package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.exception.common.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/** 用户明确维护的私有跨会话偏好；不存储权限、批准或运行事实。 */
@Component
public class AiHarnessPreferences {
  private static final Pattern REMEMBER=Pattern.compile("(?:以后|今后|往后|后面都|后续都|(?:后续|之后|未来|每次)(?:的)?(?:对话|会话|聊天|回答|回复)|跨会话|跨对话).{0,100}(?:使用|采用|保持|沿用|按|用|回答|输出|写|设置|设定|配置|选择|优先|这样|照样)|(?:记住|记下).{1,100}|保存.{0,60}(?:偏好|回答习惯|回复习惯|对话设定|对话设置|回答风格|回复风格)|(?i:remember|save).{0,60}(?i:preference|conversation setting)|(?i:future conversations|from now on|always use).{0,100}");
  private static final Pattern FORGET=Pattern.compile("(?:清空|删除|忘记|清除).{0,20}(?:偏好|回答习惯|回复习惯|对话设定|对话设置|保存的设定|保存的设置)|(?i:forget|clear|delete).{0,30}(?i:preferences|conversation settings)");
  private static final Pattern DENY=Pattern.compile("(?:不要|不用|无需|不必|别).{0,12}(?:保存|记住|记下|沿用)|(?i:do not|don't).{0,20}(?i:save|remember)");
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
  /** 只根据本轮真实用户文字开放提案，不把工具输出或卡片标题当成意图。 */
  public boolean requested(ObjectNode turn) {
    String text=userText(turn);return !DENY.matcher(text).find()&&(REMEMBER.matcher(text).find()||FORGET.matcher(text).find());
  }
  /** 续接轮次只提取用户实际填写的答案，排除平台拼接的问题和执行结果。 */
  private String userText(ObjectNode turn) {
    if(!turn.has("interactionResponse"))return turn.path("content").asText();
    var text=new StringBuilder();turn.at("/interactionResponse/answers").elements().forEachRemaining(v->text.append(v.asText()).append('\n'));
    if(turn.has("preferenceRequest"))text.append(turn.path("preferenceRequest").asText());return text.toString();
  }
  /** 必要澄清可延续原用户意图，确认或拒绝后不再携带。 */
  public String continuationRequest(ObjectNode turn){return requested(turn)?userText(turn):"";}
  /** 模型只能提出有用户原话支撑的候选，冻结完整结果与修订供用户审阅。 */
  public ObjectNode propose(ObjectNode args,ObjectNode turn) {
    args.fieldNames().forEachRemaining(k->{if(!Set.of("action","values","sourceQuote").contains(k))invalid();});
    String quote=args.path("sourceQuote").asText(),action=args.path("action").asText();
    if(!requested(turn)||!args.path("sourceQuote").isTextual()||quote.isBlank()||quote.length()>1000||!userText(turn).contains(quote)||DENY.matcher(quote).find())invalid();
    if(!Set.of("merge","clear").contains(action)||action.equals("clear")&&!FORGET.matcher(quote).find()||action.equals("merge")&&!REMEMBER.matcher(quote).find())invalid();
    validate(args.path("values"));if(action.equals("clear")&&!args.path("values").isEmpty()||action.equals("merge")&&args.path("values").isEmpty())invalid();
    var old=get();var values=action.equals("clear")?mapper.createObjectNode():(ObjectNode)old.path("values").deepCopy();
    if(action.equals("merge"))values.setAll((ObjectNode)args.path("values"));
    if(values.equals(old.path("values")))throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_PREFERENCE_UNCHANGED","这些设定已保存，无需重复确认");
    var card=mapper.createObjectNode().put("id",UUID.randomUUID().toString()).put("kind","preferences").put("state","PENDING")
        .put("title",action.equals("clear")?"清空已保存的对话偏好？":"将这些设定用于后续对话？").put("action",action).put("sourceQuote",quote)
        .put("preferenceRevision",old.path("revision").asLong()).put("expiresAt",Instant.now().plusSeconds(86400).toString())
        .put("scope","仅当前账号可见；本轮要求优先。偏好不会授予日志读取、部署或重启权限。需要调整时，请直接在对话中说明。");
    card.set("values",values);card.set("changes",args.path("values").deepCopy());card.set("previousValues",old.path("values").deepCopy());card.putArray("questions");card.putArray("options");return card;
  }
  /** 在回答事务内使用服务端冻结的候选保存，不能信任浏览器回传的偏好内容。 */
  public ObjectNode confirm(ObjectNode card) {
    return save(mapper.createObjectNode().put("revision",card.path("preferenceRevision").asLong()).set("values",card.path("values").deepCopy()));
  }
  /** 显式确认后的内部保存入口，沿用元数据乐观锁。 */
  public ObjectNode save(ObjectNode input) {
    var values=input.path("values");validate(values);
    var row=mapper.createObjectNode().put("owner",access.actor()).put("source","USER_EXPLICIT");row.set("values",values.deepCopy());
    return records.save("ai_harness_preferences",access.actor(),row,input.path("revision").asLong());
  }
  /** 字段白名单和敏感内容限制同时用于提案与确认，授权不能进入长期偏好。 */
  private void validate(com.fasterxml.jackson.databind.JsonNode values) {
    if(!values.isObject()||values.size()>5)invalid();
    values.fields().forEachRemaining(f->{
      if(!Set.of("responseStyle","defaultCatalogId","defaultSessionId","sqlStyle","logLines").contains(f.getKey())||!f.getValue().isTextual()||f.getValue().asText().length()>300)invalid();
      if(f.getKey().equals("logLines")&&!f.getValue().asText().matches("[1-9][0-9]{0,3}|10000"))invalid();
    });
    guard.requireSafe(values.toString());
  }
  /** 不合规字段不会被持久化成隐含授权。 */
  private void invalid(){throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_INVALID_PREFERENCE","偏好格式或字段无效");}
}
