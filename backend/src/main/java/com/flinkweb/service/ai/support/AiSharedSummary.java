package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.job.JobRepository;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.permission.AccessService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 按SQL草稿或JAR生命周期保存唯一总结，两入口共用，私有消息仍分别保存。 */
@Component
public class AiSharedSummary {
  private final RecordRepository records;
  private final JobRepository jobs;
  private final AccessService access;
  private final ObjectMapper mapper;

  /** 直接使用仓库解析关联，避免会话服务与作业生命周期之间形成循环依赖。 */
  public AiSharedSummary(RecordRepository records,JobRepository jobs,AccessService access,ObjectMapper mapper) {
    this.records=records;this.jobs=jobs;this.access=access;this.mapper=mapper;
  }

  /** SQL总结绑定创建时间，已发布SQL与作业入口得到同一个总结标识。 */
  public ObjectNode sql(String id) {
    var draft=records.require("sql_jobs",id);
    return read("sql:"+id+":"+draft.path("createdAt").asText(),draft.path("jobId").asText(),id);
  }

  /** 只按明确jobId关联SQL，不以相同名称推断关系。 */
  public ObjectNode job(String id) {
    var sql=records.list("sql_jobs").stream().filter(s->id.equals(s.path("jobId").asText())).findFirst();
    if(sql.isPresent()) return sql(sql.get().path("id").asText());
    var draft=jobs.findById(id).orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"JOB_NOT_FOUND","作业草稿不存在"));
    return read("job:"+id+":"+draft.createdAt(),id,"");
  }

  /** 首次使用兼容当前用户旧总结；规范总结存在后始终以唯一记录为准。 */
  private ObjectNode read(String scope,String jobId,String sqlId) {
    String id=UUID.nameUUIDFromBytes(scope.getBytes(StandardCharsets.UTF_8)).toString();
    var found=records.find("ai_summaries",id);
    if(found!=null) {
      if(!jobId.isBlank() && !jobId.equals(found.path("jobId").asText()))
        return records.save("ai_summaries",id,found.put("jobId",jobId),found.path("revision").asLong());
      return found;
    }
    var empty=mapper.createObjectNode().put("id",id).put("jobId",jobId).put("sqlId",sqlId).put("summary","").put("revision",0);
    empty.putArray("unknowns");
    var texts=new LinkedHashSet<String>();var unknowns=new LinkedHashSet<String>();
    for(String collection:List.of("ai_conversations","ai_job_conversations"))
      for(var old:records.list(collection)) {
        boolean subject=collection.equals("ai_conversations")?!sqlId.isBlank() && sqlId.equals(old.path("sqlId").asText())
            :!jobId.isBlank() && jobId.equals(old.path("jobId").asText()) && access.actor().equals(old.path("owner").asText());
        if(collection.equals("ai_conversations") && subject) {
          var draft=records.require("sql_jobs",sqlId);
          String privateId=UUID.nameUUIDFromBytes((access.actor()+"\0"+sqlId+"\0"+draft.path("createdAt").asText()).getBytes(StandardCharsets.UTF_8)).toString();
          subject=privateId.equals(old.path("id").asText());
        }
        if(subject && !old.path("summary").asText().isBlank()) {
          texts.add(old.path("summary").asText());
          for(var item:old.path("unknowns")) if(!item.asText().isBlank())unknowns.add(item.asText());
          if(collection.equals("ai_conversations"))empty.put("sqlHash",old.path("summarySqlHash").asText());
        }
      }
    if(!texts.isEmpty()) {
      empty.put("summary",String.join("\n\n",texts));
      unknowns.forEach(empty.withArray("unknowns")::add);
      return records.save("ai_summaries",id,empty,0);
    }
    return empty;
  }

  /** 总结乐观锁跨入口生效，迟到模型结果不能覆盖另一入口刚更新的总结。 */
  @Transactional
  public ObjectNode update(ObjectNode identity,long expected,String text,com.fasterxml.jackson.databind.JsonNode unknowns,String sqlHash) {
    var current=records.find("ai_summaries",identity.path("id").asText());
    long actual=current==null?0:current.path("revision").asLong();
    requireRevision(actual,expected);
    var next=identity.deepCopy().put("summary",text).put("sqlHash",sqlHash);
    next.set("unknowns",unknowns.deepCopy());
    return records.save("ai_summaries",identity.path("id").asText(),next,expected);
  }

  /** 统一校验总结修订，返回可被前端重新加载恢复的冲突。 */
  public void requireRevision(long current,long expected) {
    if(current!=expected) throw new BusinessException(HttpStatus.CONFLICT,"AI_SUMMARY_CHANGED","作业总结已由另一入口更新，请重新加载并整理后重试");
  }

  /** 兼容原会话响应，视图带总结修订；实际会话正文不再保存第二份总结。 */
  public ObjectNode view(ObjectNode conversation,ObjectNode memory,boolean sql) {
    boolean changed=conversation.has("sharedSummaryRevision")
        ?conversation.path("sharedSummaryRevision").asLong()!=memory.path("revision").asLong()
        :!memory.path("summary").asText().isBlank();
    conversation.put("summaryId",memory.path("id").asText()).put("summaryRevision",memory.path("revision").asLong()).put("summary",memory.path("summary").asText());
    conversation.set("unknowns",memory.path("unknowns").deepCopy());
    if(sql && changed) conversation.put("confirmedVersion",-1).put("scopeAccepted",false)
        .put("summaryVersion",conversation.path("contentVersion").asLong()).put("summarySqlHash",memory.path("sqlHash").asText());
    return conversation;
  }
}
