package com.flinkweb.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.AiConversationService;
import com.flinkweb.service.ai.support.AiContentGuard;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.SqlJobService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 会话保存独立于 SQL 修订；旧草稿重建、其他用户和迟到回复不能串入当前会话。 */
@Service
public class AiConversationServiceImpl implements AiConversationService {
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final SqlJobService sqlJobs;
  private final AccessService access;
  private final AiContentGuard guard;
  private final com.flinkweb.service.ai.support.AiSharedSummary memory;

  /** 复用现有 JSON 元数据存储、草稿权限及凭据防护，不创建另一套身份或数据库。 */
  public AiConversationServiceImpl(RecordRepository records, ObjectMapper mapper,
      SqlJobService sqlJobs, AccessService access, AiContentGuard guard,com.flinkweb.service.ai.support.AiSharedSummary memory) {
    this.memory=memory;
    this.records = records;
    this.mapper = mapper;
    this.sqlJobs = sqlJobs;
    this.access = access;
    this.guard = guard;
  }

  /** 会话身份还绑定草稿创建时间，删除后同名重建不会恢复旧对话。 */
  public ObjectNode get(String sqlId) {
    var draft = sqlJobs.get(sqlId);
    String id = UUID.nameUUIDFromBytes((access.actor() + "\0" + sqlId + "\0"
        + draft.path("createdAt").asText()).getBytes(StandardCharsets.UTF_8)).toString();
    var value = records.find("ai_conversations", id);
    if (value != null) return memory.view(value,memory.sql(sqlId),true);
    var empty = mapper.createObjectNode().put("id", id).put("sqlId", sqlId)
        .put("revision", 0).put("contentVersion", 0).put("summaryVersion", -1)
        .put("confirmedVersion", -1).put("summary", "");
    empty.putArray("messages");
    empty.putArray("unknowns");
    return memory.view(empty,memory.sql(sqlId),true);
  }

  /** 消息标识去重先于修订检查，网络丢响应后重试不会追加两份消息。 */
  @Transactional
  public ObjectNode append(String sqlId, ObjectNode input) {
    access.require("sql:" + sqlId, "edit");
    var value = get(sqlId);
    String id = input.path("messageId").asText(), content = input.path("content").asText().trim();
    String mode = input.path("mode").asText("requirement");
    if (!id.matches("[a-f0-9-]{36}") || content.isBlank() || content.length() > 8000
        || !Set.of("requirement", "error", "explain").contains(mode))
      fail("INVALID_AI_MESSAGE", "消息不能为空、最多8000字符，且需有效标识及消息类型");
    guard.requireSafe(content);
    for (var message : value.path("messages"))
      if (id.equals(message.path("id").asText())) {
        if (!content.equals(message.path("content").asText()) || !mode.equals(message.path("mode").asText()))
          fail("AI_MESSAGE_CONFLICT", "消息标识已经用于不同内容");
        return value;
      }
    requireRevision(value, input);
    if (value.path("messages").size() >= 118 || textLength(value) + content.length() > 36000)
      fail("AI_CONVERSATION_LIMIT", "当前会话内容已达上限，本条未发送；已有对话记录完整保留");
    value.withArray("messages").addObject().put("id", id).put("role", "user")
        .put("content", content).put("mode", mode).put("createdAt", Instant.now().toString());
    value.put("contentVersion", value.path("contentVersion").asLong() + 1).put("confirmedVersion", -1).put("scopeAccepted", false);
    return save(value);
  }

  /** 人工修改总结同样使旧建议失效，所有消息仍完整保留。 */
  @Transactional
  public ObjectNode summary(String sqlId, ObjectNode input) {
    access.require("sql:" + sqlId, "edit");
    var value = get(sqlId);
    requireRevision(value, input);
    String text = input.path("summary").asText().trim();
    if (text.isBlank() || text.length() > 8000) fail("INVALID_AI_SUMMARY", "需求总结应为1–8000字符");
    guard.requireSafe(text);
    String sql = input.path("sql").asText();
    guard.requireSafe(sql);
    long version = value.path("contentVersion").asLong() + 1;
    value.put("contentVersion", version).put("summaryVersion", version)
        .put("confirmedVersion", -1).put("scopeAccepted", false).put("summary", text).put("summarySqlHash", hash(sql));
    memory.requireRevision(value.path("summaryRevision").asLong(),input.path("summaryRevision").asLong(value.path("summaryRevision").asLong()));
    var unknowns=memory.reviewedUnknowns(input,value.path("unknowns"));guard.requireSafe(unknowns.toString());
    var shared=memory.update(memory.sql(sqlId),value.path("summaryRevision").asLong(),text,unknowns,hash(sql));
    value.put("summaryRevision",shared.path("revision").asLong());
    return save(value);
  }

  /** 确认只能作用于已覆盖全部补充的总结，SQL 改动必须重新整理或核对。 */
  @Transactional
  public ObjectNode confirm(String sqlId, ObjectNode input) {
    access.require("sql:" + sqlId, "edit");
    var value = get(sqlId);
    requireRevision(value, input);
    memory.requireRevision(value.path("summaryRevision").asLong(),input.path("summaryRevision").asLong(value.path("summaryRevision").asLong()));
    long version = value.path("contentVersion").asLong();
    if (value.path("summary").asText().isBlank()
        || value.path("summaryVersion").asLong(-1) != version)
      fail("AI_SUMMARY_STALE", "请先整理本次全部对话，再核对并确认总结");
    if (!value.path("unknowns").isEmpty() && !input.path("acceptCurrentScope").asBoolean())
      fail("AI_REQUIREMENTS_INCOMPLETE", "请补充待核对事项，或明确按当前总结中已知范围确认");
    guard.requireSafe(input.path("sql").asText());
    if (!hash(input.path("sql").asText()).equals(value.path("summarySqlHash").asText()))
      fail("AI_SQL_CONTEXT_CHANGED", "SQL 已变化，请重新整理对话后再确认");
    value.put("confirmedVersion", version).put("scopeAccepted", input.path("acceptCurrentScope").asBoolean());
    return save(value);
  }

  /** 所有持久化消息参与上下文；不允许客户端以省略历史或伪造总结绕过确认。 */
  public ObjectNode context(String sqlId, long version, String mode, String sql) {
    access.require("sql:" + sqlId, "edit");
    var value = get(sqlId);
    if (!Set.of("chat", "explain", "generate", "repair").contains(mode)
        || value.path("contentVersion").asLong() != version
        || (value.path("messages").isEmpty() && value.path("summary").asText().isBlank()))
      fail("AI_CONVERSATION_CHANGED", "会话已更新，请重新读取本次对话后重试");
    if (Set.of("generate", "repair").contains(mode)
        && (value.path("confirmedVersion").asLong(-1) != version
            || !hash(sql).equals(value.path("summarySqlHash").asText())))
      fail("AI_SUMMARY_NOT_CONFIRMED", "请先确认当前 SQL 对应的最新需求总结");
    return mapper.createObjectNode().set("conversation", value.deepCopy());
  }

  /** 取消由任务终态锁保护；并发补充则以内容版本拒绝迟到回复。 */
  @Transactional
  public void complete(String sqlId, long version, String taskId, String mode, String sqlHash, long summaryRevision, ObjectNode result, Runnable commitTask) {
    var value = get(sqlId);
    memory.requireRevision(value.path("summaryRevision").asLong(),summaryRevision);
    if (value.path("contentVersion").asLong() != version)
      fail("AI_CONVERSATION_CHANGED", "会话已补充，本次旧回复未写入，请重新整理");
    boolean generation = Set.of("generate", "repair").contains(mode);
    String reply = generation ? "已按确认总结生成 SQL 建议，请审阅并显式应用。" : result.path("reply").asText();
    if (reply.isBlank() || reply.length() > 4000 || textLength(value) + reply.length() > 40000)
      fail("AI_INVALID_OUTPUT", "AI 对话回复为空或过长，请缩小本次问题后重试");
    value.withArray("messages").addObject().put("id", taskId).put("role", "assistant")
        .put("content", reply).put("taskId", taskId).put("createdAt", Instant.now().toString());
    if (generation) value.put("lastSqlTaskId", taskId);
    else {
      String text = result.path("summary").asText();
      if (text.isBlank() || text.length() > 8000) fail("AI_INVALID_OUTPUT", "模型未返回完整需求总结或总结过长");
      value.put("summary", text).put("summaryVersion", version).put("summarySqlHash", sqlHash)
          .put("confirmedVersion", -1).put("scopeAccepted", false);
      value.set("unknowns", result.path("unknowns").deepCopy());
      var shared=memory.update(memory.sql(sqlId),summaryRevision,text,result.path("unknowns"),sqlHash);
      value.put("summaryRevision",shared.path("revision").asLong());
    }
    save(value);
    commitTask.run();
  }

  /** 统计完整消息文本，达到边界明确拒绝，不静默截断会话。 */
  private int textLength(ObjectNode value) {
    int length = 0;
    for (var message : value.path("messages")) length += message.path("content").asText().length();
    return length;
  }

  /** 乐观锁拒绝覆盖另一窗口已保存的消息或总结。 */
  private void requireRevision(ObjectNode value, ObjectNode input) {
    if (!input.path("revision").canConvertToLong() || input.path("revision").asLong(-1) != value.path("revision").asLong())
      throw new BusinessException(HttpStatus.CONFLICT, "REVISION_CONFLICT", "对话已更新，请重新加载后重试");
  }

  /** 只写会话集合，不改变 SQL 草稿、部署或运行状态。 */
  private ObjectNode save(ObjectNode value) {
    value.put("sharedSummaryRevision",value.path("summaryRevision").asLong());
    var stored=value.deepCopy();stored.remove(List.of("summary","summaryRevision","summaryId","unknowns"));
    var saved=records.save("ai_conversations", value.path("id").asText(), stored, value.path("revision").asLong());
    return memory.view(saved,memory.sql(value.path("sqlId").asText()),true);
  }

  /** 以摘要绑定当前 SQL，不重复持久化输入脚本。 */
  public static String hash(String text) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) { throw new IllegalStateException(error); }
  }

  /** 返回可读的业务校验错误。 */
  private void fail(String code, String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, code, message);
  }
}
