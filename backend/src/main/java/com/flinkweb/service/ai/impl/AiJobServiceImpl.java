package com.flinkweb.service.ai.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.AiJobService;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.permission.AccessService;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 以作业生命周期和用户隔离记忆，成功总结后压缩原文并保留有限证据及方案。 */
@Service
public class AiJobServiceImpl implements AiJobService {
  private final ObjectMapper mapper;
  private final RecordRepository records;
  private final JobService jobs;
  private final AccessService access;
  private final AiContentGuard guard;
  private final AiJobState state;
  private final AiJobEvidenceCollector evidence;
  private final AiJobRepairCoordinator repairs;
  private final TransactionTemplate transaction;

  /** 注入已分离的会话存储、证据采集和确定性修复编排。 */
  public AiJobServiceImpl(ObjectMapper mapper,RecordRepository records,JobService jobs,AccessService access,
      AiContentGuard guard,AiJobState state,AiJobEvidenceCollector evidence,AiJobRepairCoordinator repairs,PlatformTransactionManager manager) {
    this.mapper=mapper;this.records=records;this.jobs=jobs;this.access=access;this.guard=guard;this.state=state;
    this.evidence=evidence;this.repairs=repairs;this.transaction=new TransactionTemplate(manager);
  }

  /** 生命周期身份绑定草稿创建时间，作业重启延续记忆，同名重建隔离旧记录。 */
  private ObjectNode load(String jobId) {
    access.require(jobId,"read");
    var job=jobs.get(jobId);
    String id=UUID.nameUUIDFromBytes((access.actor()+"\0"+jobId+"\0"+job.createdAt()).getBytes(StandardCharsets.UTF_8)).toString();
    var value=records.find("ai_job_conversations",id);
    if(value!=null) return value;
    var empty=mapper.createObjectNode().put("id",id).put("jobId",jobId).put("owner",access.actor())
        .put("jobCreatedAt",job.createdAt().toString()).put("revision",0).put("contentVersion",0)
        .put("summary","").put("compressedMessages",0);
    empty.putArray("messages");empty.putArray("unknowns");return empty;
  }

  /** 恢复时仅访问元数据；运行状态仍由原页面手动刷新。 */
  public ObjectNode get(String jobId) {
    synchronized(state.monitor()) {
      var value=load(jobId);prune(jobId);
      var bundle=records.find("ai_job_evidence",value.path("id").asText());
      if(bundle!=null) value.set("evidence",bundle);
      var plan=records.find("ai_job_plans",value.path("lastPlanId").asText());
      if(plan!=null) {
        boolean current=plan.path("contentVersion").asLong()==value.path("contentVersion").asLong()
            && evidence.matches(jobs.get(jobId),plan.path("baseline")) && !plan.hasNonNull("applicationId");
        value.set("plan",plan.put("canApply",current));
        if(plan.hasNonNull("applicationId")) value.set("application",repairs.application(jobId,plan.path("applicationId").asText()));
      }
      var tasks=records.listForSubject("ai_tasks",jobId).stream().filter(t->jobId.equals(t.path("jobId").asText())
          && access.actor().equals(t.path("owner").asText())).sorted(order()).toList();
      if(!tasks.isEmpty()) value.set("lastTask",tasks.get(0).deepCopy().without("requestHash"));
      boolean developer=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
          .anyMatch(a->Set.of("ROLE_ADMIN","ROLE_DEVELOPER").contains(a.getAuthority()));
      var sql=evidence.sql(jobId);
      value.put("canEdit",developer && access.allowed(jobId,"edit") && (sql==null || access.allowed("sql:"+sql.path("id").asText(),"edit")));
      value.put("canOperate",access.allowed(jobId,"operate"));
      return value;
    }
  }

  /** 消息只由用户提交，模型回复只能从任务完成回调写入。 */
  public ObjectNode append(String jobId,ObjectNode input) {
    synchronized(state.monitor()) {
      var value=load(jobId);requireSettledApplication(jobId);
      String id=input.path("messageId").asText(),content=input.path("content").asText().trim(),mode=input.path("mode").asText("chat");
      if(!id.matches("[a-f0-9-]{36}") || content.isBlank() || content.length()>8000 || !Set.of("chat","repair").contains(mode))
        fail("AI_INVALID_MESSAGE","请输入1到8000字符的问题，并使用有效的消息标识和模式");
      guard.requireSafe(content);
      for(var message:value.path("messages")) if(id.equals(message.path("id").asText())) {
        if(!content.equals(message.path("content").asText()) || !mode.equals(message.path("mode").asText())) conflict("消息号已用于其他内容");
        return get(jobId);
      }
      revision(value,input);
      if(value.path("messages").size()>=26 || value.path("messages").toString().length()+content.length()>36000)
        fail("AI_MEMORY_LIMIT","尚未成功总结的原文已达预算，请重试整理当前对话；已有消息完整保留");
      value.withArray("messages").addObject().put("id",id).put("role","user").put("content",content).put("mode",mode).put("createdAt",Instant.now().toString());
      value.put("contentVersion",value.path("contentVersion").asLong()+1);
      save(value);return get(jobId);
    }
  }

  /** 人工修正总结立即使旧方案失效，不删除原未知项。 */
  public ObjectNode summary(String jobId,ObjectNode input) {
    synchronized(state.monitor()) {
      var value=load(jobId);revision(value,input);
      String text=input.path("summary").asText().trim();
      if(text.isBlank() || text.length()>4000) fail("AI_INVALID_SUMMARY","总结应为1到4000字符");
      guard.requireSafe(text);
      value.put("summary",text).put("contentVersion",value.path("contentVersion").asLong()+1).put("manualSummary",true);
      save(value);return get(jobId);
    }
  }

  /** 远端取证不持数据库事务；采集后再核对会话与草稿，拒绝已变化的上下文。 */
  public ObjectNode context(String jobId,long version,boolean includeLogs) {
    ObjectNode conversation;
    com.flinkweb.entity.job.JobDraft job;
    synchronized(state.monitor()) {conversation=load(jobId);requireSettledApplication(jobId);requireVersion(conversation,version);job=jobs.get(jobId);}
    var bundle=evidence.collect(job,includeLogs);
    synchronized(state.monitor()) {
      requireVersion(load(jobId),version);
      if(!evidence.matches(jobs.get(jobId),bundle.path("baseline"))) conflict("取证期间 SQL 或配置已变化，请重新发送");
      String id=conversation.path("id").asText();
      bundle.put("jobId",jobId).put("owner",access.actor()).put("contentVersion",version);
      var old=records.find("ai_job_evidence",id);
      records.save("ai_job_evidence",id,bundle,old==null?0:old.path("revision").asLong());
      var context=mapper.createObjectNode().put("jobId",jobId).put("jobName",job.name()).put("flinkVersion",job.flinkVersion());
      context.set("conversation",conversation);context.set("evidence",bundle);
      if(bundle.has("currentSql")) context.set("currentSql",bundle.get("currentSql"));
      if(context.toString().length()>90000) fail("AI_INPUT_TOO_LARGE","总结、近期对话及证据超过预算，请缩小本次问题范围");
      return context;
    }
  }

  /** 成功回复与总结原子持久化；较早消息仅在总结覆盖后裁剪到最近八轮。 */
  public void complete(String jobId,long version,String taskId,ObjectNode context,ObjectNode result) {
    synchronized(state.monitor()) {
      var value=load(jobId);requireVersion(value,version);
      var job=jobs.get(jobId);
      if(!evidence.matches(job,context.at("/evidence/baseline"))) conflict("分析期间 SQL 或配置已变化，旧回复未应用");
      String summary=result.path("summary").asText(),reply=result.path("reply").asText();
      if(summary.isBlank() || summary.length()>4000 || reply.isBlank() || reply.length()>4000)
        fail("AI_INVALID_OUTPUT","模型应返回1到4000字符的完整总结和回复，原消息未压缩");
      var plan="repair".equals(context.path("mode").asText())?repairs.propose(job,(ObjectNode)context.path("evidence"),result,version,access.actor()):null;
      transaction.executeWithoutResult(status->{
        if(plan!=null) {
          var saved=records.save("ai_job_plans",UUID.randomUUID().toString(),plan,0);
          value.put("lastPlanId",saved.path("id").asText());result.put("planId",saved.path("id").asText());
        }
        value.put("summary",summary).put("summaryThroughVersion",version).put("lastTaskId",taskId);
        value.set("unknowns",result.path("unknowns").deepCopy());
        value.withArray("messages").addObject().put("id",taskId).put("role","assistant").put("content",reply).put("createdAt",Instant.now().toString());
        var messages=value.withArray("messages");
        if(messages.size()>24) {
          int remove=messages.size()-16;
          for(int i=0;i<remove;i++)messages.remove(0);
          value.put("compressedMessages",value.path("compressedMessages").asInt()+remove);
        }
        // 同时施加字符预算；完整总结已持久化后，才删除被覆盖的较早原文。
        while(messages.size()>2 && messages.toString().length()>28000) {
          messages.remove(0);value.put("compressedMessages",value.path("compressedMessages").asInt()+1);
        }
        save(value);prune(jobId);
      });
      result.remove(List.of("changes","sql"));
    }
  }

  /** 基准、私有会话和确认状态均重新核验；完成前不允许其它生命周期写入。 */
  public ObjectNode apply(String jobId,String planId,ObjectNode input,Principal actor) {
    synchronized(state.monitor()) {
      var value=load(jobId);
      var plan=records.require("ai_job_plans",planId);
      if(!jobId.equals(plan.path("jobId").asText()) || !access.actor().equals(plan.path("owner").asText()))
        throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权应用其他用户的修复方案");
      if(records.find("ai_job_applications",input.path("requestId").asText())==null) {
        for(var pending:records.listForSubject("ai_job_applications",jobId))
          if(access.actor().equals(pending.path("owner").asText()) && Set.of("SAVING","SUBMITTING","UNKNOWN").contains(pending.path("state").asText())) {
            var operation=records.find("operations",pending.path("operationId").asText());
            if(operation==null || Set.of("SUBMITTING","UNKNOWN").contains(operation.path("state").asText()))
              conflict("仍有未确认的原应用 "+pending.path("id").asText()+"，请先核对并继续原应用，不能创建第二次提交");
          }
        if(!planId.equals(value.path("lastPlanId").asText()) || plan.path("contentVersion").asLong()!=value.path("contentVersion").asLong())
          conflict("对话或总结已更新，请重新生成并审阅方案");
        if(!input.path("confirmed").asBoolean()) fail("AI_CONFIRMATION_REQUIRED","请确认已审阅完整变更后再应用");
      }
      var application=repairs.apply(jobId,plan,input,actor);prune(jobId);return application;
    }
  }

  /** 查询应用不推动运行；平台重启后的中间态明确显示需要核对。 */
  public ObjectNode application(String jobId,String requestId) {
    load(jobId);return repairs.application(jobId,requestId);
  }

  /** 持久化会话正文，临时展示字段不得混入下一次模型上下文。 */
  private ObjectNode save(ObjectNode value) {
    value.remove(List.of("plan","evidence","application","lastTask","canEdit","canOperate"));
    return records.save("ai_job_conversations",value.path("id").asText(),value,value.path("revision").asLong());
  }

  /** 每个用户作业只保留有限任务、方案与应用记录，不累计无限旧正文。 */
  private void prune(String jobId) {
    for(String collection:List.of("ai_tasks","ai_job_plans","ai_job_applications")) {
      int limit=collection.equals("ai_tasks")?5:collection.equals("ai_job_plans")?2:3;
      var list=records.listForSubject(collection,jobId).stream().filter(r->jobId.equals(r.path("jobId").asText())
          && access.actor().equals(r.path("owner").asText()))
          .filter(r->!Set.of("QUEUED","RUNNING","SAVING","SUBMITTING","UNKNOWN").contains(r.path("state").asText())).sorted(order()).toList();
      for(var old:list.stream().skip(limit).toList()) records.delete(collection,old.path("id").asText(),old.path("revision").asLong());
    }
  }

  /** 原应用未确认时先核对原号，防止后续方案覆盖并裁剪恢复入口。 */
  private void requireSettledApplication(String jobId) {
    for(var pending:records.listForSubject("ai_job_applications",jobId)) {
      if(!access.actor().equals(pending.path("owner").asText())) continue;
      String status=pending.path("state").asText();
      var operation=records.find("operations",pending.path("operationId").asText());
      if(operation!=null) status=operation.path("state").asText();
      if(Set.of("SAVING","SUBMITTING","UNKNOWN").contains(status)
          || (status.equals("SAVED") && pending.path("mode").asText().equals("restart")))
        conflict("原应用尚未确认，请先核对并继续原应用，完成前不能生成新方案");
    }
  }

  /** 稳定按真实时间排序，避免可变小数位时间字符串顺序错误。 */
  private Comparator<ObjectNode> order() {return Comparator.comparing((ObjectNode n)->Instant.parse(n.path("createdAt").asText())).thenComparing(n->n.path("id").asText()).reversed();}
  /** 拒绝浏览器覆盖并发更新的总结或消息。 */
  private void revision(ObjectNode value,ObjectNode input) {if(value.path("revision").asLong()!=input.path("revision").asLong(-1)) conflict("会话已变化，请重新读取后重试");}
  /** 拒绝已补充、已删除或不再对应同一轮消息的模型结果。 */
  private void requireVersion(ObjectNode value,long version) {if(value.path("contentVersion").asLong()!=version || value.path("messages").isEmpty()) conflict("会话已变化，请重新发送本次问题");}
  /** 参数错误使用稳定代码。 */
  private void fail(String code,String message) {throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
  /** 并发与过期结果都不允许静默回填。 */
  private void conflict(String message) {throw new BusinessException(HttpStatus.CONFLICT,"AI_CONVERSATION_CHANGED",message);}
}
