package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 按角色和阶段发现能力，所有目标来自服务端会话，不接受模型传入地址或资源身份。 */
@Component
public class AiHarnessTools {
  private final ObjectMapper mapper;
  private final JobService jobs;
  private final SqlJobService sql;
  private final AiJobEvidenceCollector evidence;
  private final AiSharedSummary memory;
  private final AiJobRepairCoordinator repairs;
  private final AiContentGuard guard;
  private final AccessService access;
  private final RecordRepository records;

  /** 只组合现有领域能力，模型不获得写入凭据。 */
  public AiHarnessTools(ObjectMapper mapper,JobService jobs,SqlJobService sql,AiJobEvidenceCollector evidence,
      AiSharedSummary memory,AiJobRepairCoordinator repairs,AiContentGuard guard,AccessService access,RecordRepository records){
    this.mapper=mapper;this.jobs=jobs;this.sql=sql;this.evidence=evidence;this.memory=memory;this.repairs=repairs;this.guard=guard;this.access=access;this.records=records;
  }

  /** 工具返回值与待持久化产物分离，防止内部字段混进模型消息。 */
  public record Outcome(ObjectNode output,ObjectNode proposal,ObjectNode evidence,ObjectNode requirements) {}

  /** 每轮重建来源、版本和共享总结，不导入其他会话正文。 */
  public ObjectNode context(ObjectNode session){
    var out=mapper.createObjectNode().put("profile",session.path("kind").asText());
    String jobId=session.path("jobId").asText(),sqlId=session.path("sqlId").asText();
    if(session.path("kind").asText().equals("diagnosis")){
      var job=jobs.get(jobId);if(!job.createdAt().toString().equals(session.path("subjectCreatedAt").asText()))stale();
      out.put("jobName",job.name()).put("flinkVersion",job.flinkVersion());out.set("current",evidence.current(job));
      out.set("baseline",evidence.baseline(job));out.set("sharedSummary",memory.job(jobId));
    }else if(session.path("kind").asText().equals("sql")){
      var draft=sql.get(sqlId);if(!draft.path("createdAt").asText().equals(session.path("subjectCreatedAt").asText()))stale();
      guard.requireSafe(draft.path("sql").asText());out.set("draft",draft);out.set("sharedSummary",memory.sql(sqlId));
      out.putObject("baseline").put("sqlRevision",draft.path("revision").asLong()).put("sqlHash",SqlDeploymentSource.hash(draft.path("sql").asText()));
    }else {
      var source=records.require("ai_harness_sessions",session.path("sourceSessionId").asText());
      if(!access.actor().equals(source.path("owner").asText()))deny();
      out.put("sourceSessionId",source.path("id").asText()).put("evaluationScope","本轮开始时冻结的真实轨迹结构检查，不证明模型答案或业务结果正确");
      out.putObject("baseline").put("sourceRevision",source.path("revision").asLong()).put("sourceSequence",source.path("sequence").asLong());
      out.set("trace",evaluate(source.path("id").asText()));
    }
    out.put("permissionPolicy","作业共享可见，操作按角色；AI 会话和任务私有");
    out.put("observationPolicy","按需读取；运行受理不代表成功；普通作业三秒手动刷新");
    return (ObjectNode)guard.sanitize(out);
  }

  /** 只向模型公开当前场景及阶段真正可用的结构化工具。 */
  public ArrayNode schemas(ObjectNode session,ObjectNode turn){
    var list=mapper.createArrayNode();
    add(list,"context_read","读取本轮已冻结的当前上下文；已有结果足够时不重复读取。",Map.of());
    add(list,"memory_update","提出本会话的精简记忆，保留用户约束、已验证事实及未知项；不更新共享作业总结或权限。",Map.of("summary","string"));
    String kind=session.path("kind").asText();
    if(kind.equals("diagnosis")){
      add(list,"evidence_collect","按类别重新采集当前作业证据；不可用不是零，不读任意目标。",Map.of("category","string"));
      if(access.allowed(session.path("jobId").asText(),"edit"))add(list,"repair_propose","生成待用户审阅的最小参数/SQL方案，不保存或重启；必须先取得本轮证据。",Map.of("changes","array","sql","string"));
    }else if(kind.equals("sql")){
      add(list,"requirements_propose","整理完整业务需求及必要缺口，供用户确认；不生成SQL或修改草稿。",Map.of("summary","string","unknowns","array"));
      add(list,"sql_check","按当前草稿运行环境检查候选SQL；Parser不是Planner或业务认证。",Map.of("sql","string"));
      if(turn.has("approvedRequirements"))add(list,"sql_propose","在明确确认的需求范围内生成完整SQL候选，仍需用户审阅后才保存。",Map.of("sql","string"));
    }else add(list,"trace_inspect","读取指定源会话的真实轨迹并执行确定性结构评分，不回放模型或部署。",Map.of());
    return list;
  }

  /** 验证工具名、额外字段和参数类型，再调用受信领域适配器。 */
  public Outcome execute(String name,ObjectNode args,ObjectNode session,ObjectNode turn){
    var schema=schemas(session,turn);JsonNode found=null;
    for(var item:schema)if(name.equals(item.at("/function/name").asText()))found=item;
    if(found==null)throw new BusinessException(HttpStatus.FORBIDDEN,"HARNESS_TOOL_DENIED","当前阶段或角色未开放该工具");
    var parameters=found.at("/function/parameters/properties");
    for(var names=args.fieldNames();names.hasNext();)if(!parameters.has(names.next()))bad("工具包含未允许参数");
    for(var fields=parameters.fields();fields.hasNext();){var field=fields.next();var value=args.path(field.getKey());String type=field.getValue().path("type").asText();
      if(type.equals("string")&&!value.isTextual()||type.equals("array")&&!value.isArray())bad("工具参数类型或必填项不正确");}
    guard.requireSafe(args.toString());
    if(name.equals("context_read"))return new Outcome((ObjectNode)turn.path("context").deepCopy(),null,null,null);
    if(name.equals("memory_update")){
      String text=args.path("summary").asText();if(text.isBlank()||text.length()>4000)bad("会话记忆应为1到4000字符");
      return new Outcome(mapper.createObjectNode().put("memoryCandidate",text).put("verificationStatus","PROPOSED"),null,null,null);
    }
    if(name.equals("evidence_collect")){
      var job=jobs.get(session.path("jobId").asText());requireBaseline(job.id(),turn);
      String category=args.path("category").asText();
      if(Set.of("logs","flinkLogs").contains(category)&&!turn.path("includeLogs").asBoolean())bad("本轮未授权日志取证和外发");
      if(category.equals("auto")&&!turn.path("includeLogs").asBoolean())category="pods";
      var bundle=evidence.collectCategory(job,category);
      if(!evidence.matches(jobs.get(job.id()),bundle.path("baseline")))stale();
      var safe=(ObjectNode)guard.sanitize(evidence.forModel(bundle));return new Outcome(safe,null,safe,null);
    }
    if(name.equals("repair_propose")){
      String jobId=session.path("jobId").asText();access.require(jobId,"edit");requireBaseline(jobId,turn);
      if(!turn.path("evidence").isObject())bad("请先取得本轮作业证据，再生成修复方案");
      var plan=repairs.propose(jobs.get(jobId),(ObjectNode)turn.path("evidence"),args,turn.path("sequence").asLong(),access.actor());
      if(plan==null)bad("方案没有实际修改");
      return new Outcome(mapper.createObjectNode().put("status","PROPOSED").put("check",plan.path("check").asText()).put("saved",false),plan,null,null);
    }
    if(name.equals("requirements_propose")){
      String text=args.path("summary").asText();if(text.isBlank()||text.length()>8000||args.path("unknowns").size()>20)bad("需求总结或待核对项超出预算");
      for(var item:args.path("unknowns"))if(!item.isTextual()||item.asText().length()>1000)bad("待核对项须为有界文本");
      return new Outcome(mapper.createObjectNode().put("status","AWAITING_REQUIREMENTS_CONFIRMATION"),null,null,args.deepCopy());
    }
    if(name.equals("sql_check")||name.equals("sql_propose")){
      var draft=sql.get(session.path("sqlId").asText());checkSqlBaseline(draft,turn);
      String script=args.path("sql").asText();if(script.isBlank()||script.length()>32768)bad("SQL 候选应为1到32768字符");
      var check=sql.check(script,(ObjectNode)draft.path("runtime"));
      var report=mapper.valueToTree(check);var out=mapper.createObjectNode();out.set("check",report);
      if(name.equals("sql_check"))return new Outcome(out,null,null,null);
      repairs.validateHarnessSql(script);if(!check.valid())bad(check.message());
      var plan=mapper.createObjectNode().put("kind","sql").put("sqlId",draft.path("id").asText()).put("sqlRevision",draft.path("revision").asLong())
          .put("sqlCreatedAt",draft.path("createdAt").asText()).put("beforeSql",draft.path("sql").asText()).put("sql",script).put("check",check.message());
      out.put("status","PROPOSED").put("saved",false);return new Outcome(out,plan,null,null);
    }
    return new Outcome((ObjectNode)turn.at("/context/trace").deepCopy(),null,null,null);
  }

  /** 只有当前冻结的草稿与内容仍一致时，才允许继续使用本轮证据。 */
  private void requireBaseline(String jobId,ObjectNode turn){if(!evidence.matches(jobs.get(jobId),turn.at("/context/baseline")))stale();}

  /** 模型及工具步骤边界核对冻结内容，不访问集群或伪造实时观测。 */
  public void validateContext(ObjectNode session,ObjectNode turn){
    if(session.path("kind").asText().equals("diagnosis"))requireBaseline(session.path("jobId").asText(),turn);
    if(session.path("kind").asText().equals("sql"))checkSqlBaseline(sql.get(session.path("sqlId").asText()),turn);
  }

  /** 手动观察原操作和协调修订，RUNNING 单独不足以证明本次变更运行。 */
  public ObjectNode observe(String jobId,String operationId,long revision){
    access.require(jobId,"read");var job=jobs.get(jobId);var live=evidence.resource(job);
    var out=mapper.createObjectNode().put("observedAt",java.time.Instant.now().toString()).put("businessVerified",false);
    if(live.isMissingNode())return out.put("jobState","MISSING").put("runningRevisionConfirmed",false);
    String uid=live.at("/metadata/uid").asText(),jobState=live.at("/status/jobStatus/state").asText("UNKNOWN");
    long generation=live.at("/metadata/generation").asLong(-1);boolean reconciled=generation>=0&&live.at("/status/observedGeneration").asLong(-2)>=generation;
    if(!reconciled&&generation>=0)try{var previous=mapper.readTree(live.at("/status/reconciliationStatus/lastReconciledSpec").asText("{}"));
      reconciled=previous.at("/metadata/generation").asLong(-2)==generation||previous.path("spec").isObject()&&com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(previous.path("spec")).equals(com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(live.path("spec")));
    }catch(Exception ignored){/* 缺少明确协调证据时保留未核验状态。 */}
    boolean matched=operationId.equals(live.at("/metadata/annotations/flink-web~1last-request").asText())&&job.revision()==revision;
    return out.put("uid",uid).put("jobState",jobState).put("operationMatched",matched).put("runningRevisionConfirmed",matched&&reconciled&&jobState.equals("RUNNING"))
        .put("message",matched?"已观察本次操作；业务效果仍需单独验收":"资源或草稿已变化，不能把当前运行当作本次操作成功");
  }
  /** SQL 版本检查不依赖浏览器返回的旧脚本。 */
  private void checkSqlBaseline(ObjectNode draft,ObjectNode turn){if(draft.path("revision").asLong()!=turn.at("/context/baseline/sqlRevision").asLong()||!SqlDeploymentSource.hash(draft.path("sql").asText()).equals(turn.at("/context/baseline/sqlHash").asText()))stale();}
  /** 评测只检查实际保存轨迹，明确无法由轨迹证明的质量指标。 */
  private ObjectNode evaluate(String sessionId){
    var source=records.require("ai_harness_sessions",sessionId);if(!access.actor().equals(source.path("owner").asText()))deny();
    var turns=records.listForSubject("ai_harness_turns",sessionId);int calls=0,failed=0;
    for(var turn:turns)for(var event:turn.path("events")){if(event.path("type").asText().equals("tool.started"))calls++;if(event.path("type").asText().equals("tool.failed"))failed++;}
    var out=mapper.createObjectNode().put("turns",turns.size()).put("toolCalls",calls).put("failedTools",failed).put("replayedWrites",0);
    var cases=out.putArray("cases");for(var turn:turns)cases.addObject().put("turnId",turn.path("id").asText()).put("state",turn.path("state").asText()).put("contextPresent",turn.has("contextManifest")).put("candidateRequiresApproval",turn.has("planId"));
    out.putArray("notMeasured").add("模型结论正确率").add("生产业务结果").add("未运行的故障注入与留出集");return out;
  }
  /** 生成严格的函数参数说明；模型声明不代替运行时校验。 */
  private void add(ArrayNode list,String name,String description,Map<String,String> fields){
    var fn=list.addObject().put("type","function").putObject("function").put("name",name).put("description",description);
    var params=fn.putObject("parameters").put("type","object").put("additionalProperties",false);var props=params.putObject("properties");var required=params.putArray("required");
    fields.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(field->{var item=props.putObject(field.getKey()).put("type",field.getValue());
      if(field.getValue().equals("array")){if(field.getKey().equals("changes")){var element=item.putObject("items").put("type","object");var p=element.putObject("properties");p.putObject("path").put("type","string");p.putObject("value").putArray("type").add("string").add("number").add("boolean").add("null");element.putArray("required").add("path").add("value");element.put("additionalProperties",false);}else item.putObject("items").put("type","string");}
      required.add(field.getKey());});
  }
  /** 未知或不合规参数不能进入领域能力。 */
  private void bad(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_INVALID_TOOL",message);}
  /** 来源变化使候选失效。 */
  private void stale(){throw new BusinessException(HttpStatus.CONFLICT,"HARNESS_CONTEXT_CHANGED","上下文或资源生命周期已变化，请重新分析并审阅");}
  /** 私有轨迹不会因为作业共享而公开。 */
  private void deny(){throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权读取其他用户的私有轨迹");}
}
