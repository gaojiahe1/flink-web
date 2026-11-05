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
  @org.springframework.beans.factory.annotation.Autowired
  private com.flinkweb.client.ai.AiTelemetry telemetry;
  private final ObjectMapper mapper;
  private final JobService jobs;
  private final SqlJobService sql;
  private final AiJobEvidenceCollector evidence;
  private final AiSharedSummary memory;
  private final AiJobRepairCoordinator repairs;
  private final AiContentGuard guard;
  private final AccessService access;
  private final RecordRepository records;
  private final com.flinkweb.config.platform.PlatformProperties properties;
  private final AiHarnessObservation observation;
  private final AiHarnessCapabilities capabilities;
  private final AiHarnessPreferences preferences;

  /** 只组合现有领域能力，模型不获得写入凭据。 */
  public AiHarnessTools(ObjectMapper mapper,JobService jobs,SqlJobService sql,AiJobEvidenceCollector evidence,
      AiSharedSummary memory,AiJobRepairCoordinator repairs,AiContentGuard guard,AccessService access,RecordRepository records,com.flinkweb.config.platform.PlatformProperties properties,AiHarnessObservation observation,AiHarnessCapabilities capabilities,AiHarnessPreferences preferences){
    this.mapper=mapper;this.jobs=jobs;this.sql=sql;this.evidence=evidence;this.memory=memory;this.repairs=repairs;this.guard=guard;this.access=access;this.records=records;this.properties=properties;this.observation=observation;this.capabilities=capabilities;
    this.preferences=preferences;
  }

  /** 工具返回值与待持久化产物分离，防止内部字段混进模型消息。 */
  public record Outcome(ObjectNode output,ObjectNode proposal,ObjectNode evidence,ObjectNode requirements) {}

  /** 用户选择的模板由服务端目录读取并冻结。 */
  public ObjectNode template(String name){return capabilities.prompt(name);}

  /** 对已保存SQL发起验证必须先审阅资源和执行影响。 */
  private Outcome verification(ObjectNode session,ObjectNode turn,ObjectNode args,boolean cancel){
    String sqlId=session.path("sqlId").asText();if(sqlId.isBlank())bad("请先审阅并保存SQL草稿，再申请运行验证");access.require("sql:"+sqlId,"edit");
    var draft=sql.get(sqlId);checkSqlBaseline(draft,turn);var card=mapper.createObjectNode().put("id",UUID.randomUUID().toString()).put("kind","verification").put("state","PENDING").put("sqlId",sqlId).put("sqlRevision",draft.path("revision").asLong()).put("action",cancel?"cancel":"submit").put("expiresAt",java.time.Instant.now().plusSeconds(86400).toString());
    if(cancel){var run=records.require("sql_validations",args.path("verificationId").asText());if(!sqlId.equals(run.path("sqlId").asText()))bad("验证任务不属于当前SQL");card.put("verificationId",run.path("id").asText());}
    card.put("title",cancel?"取消当前SQL验证任务？":"运行当前已保存SQL的短时验证？").put("scope",cancel?"将向验证Session发送取消请求，需后续核对最终状态。":"将在专用验证Session执行SQL，可能访问真实Source/Sink并产生写入；最多1000条或两分钟后清理，受限结果将发送给本轮模型。草稿："+draft.path("name").asText()+"；修订："+draft.path("revision").asLong());card.putArray("questions");card.putArray("options");
    return new Outcome(mapper.createObjectNode().set("interaction",card),null,null,null);
  }

  /** 每轮重建来源、版本和共享总结，不导入其他会话正文。 */
  public ObjectNode context(ObjectNode session){
    var out=mapper.createObjectNode().put("profile",session.path("kind").asText());
    String jobId=session.path("jobId").asText(),sqlId=session.path("sqlId").asText();
    if(session.path("kind").asText().equals("diagnosis")){
      var job=jobs.get(jobId);if(!job.createdAt().toString().equals(session.path("subjectCreatedAt").asText()))stale();
      out.put("jobName",job.name()).put("flinkVersion",job.flinkVersion());out.set("current",evidence.current(job));
      out.set("baseline",evidence.baseline(job));out.set("sharedSummary",memory.job(jobId));out.set("runtimeStatus",observation.snapshot(jobId));
    }else if(session.path("kind").asText().equals("sql")){
      var draft=sqlDraft(session);if(!draft.path("createdAt").asText().equals(session.path("subjectCreatedAt").asText()))stale();
      guard.requireSafe(draft.path("sql").asText());out.set("draft",draft);var images=out.putArray("runtimeImages");for(var image:records.list("images"))images.addObject().put("id",image.path("id").asText()).put("flinkVersion",image.path("flinkVersion").asText()).put("image",image.path("repository").asText()+":"+image.path("version").asText());if(!sqlId.isBlank())out.set("sharedSummary",memory.sql(sqlId));
      if(!draft.path("jobId").asText().isBlank()){
        var job=jobs.get(draft.path("jobId").asText());out.put("jobId",job.id());out.set("jobBaseline",evidence.baseline(job));out.set("current",evidence.current(job));out.set("runtimeStatus",observation.snapshot(job.id()));
      }
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
    add(list,"plan_update","先根据用户本次目标和冻结上下文给出1到6个具体执行步骤，再使用执行工具。计划不等于已执行，也不是变更批准。",Map.of("steps","array"));
    var intent=((ObjectNode)list.get(0).at("/function/parameters/properties")).putObject("intent").put("type","string");
    intent.putArray("enum").add("sql_development").add("status").add("diagnosis").add("repair").add("evaluation");
    if(turn.path("plan").isEmpty())return list;
    add(list,"context_read","读取本轮已冻结的当前上下文；已有结果足够时不重复读取。",Map.of());
    add(list,"memory_update","提出本会话的精简记忆，保留用户约束、已验证事实及未知项；不更新共享作业总结或权限。",Map.of("summary","string"));
    if(preferences.requested(turn)){
      add(list,"preferences_propose","用户明确要求后续对话沿用或记住设定时，提取具体偏好并提出待确认卡片，不直接保存。action=merge只修改列出的字段；action=clear清空全部偏好，values传空对象。sourceQuote必须逐字引用本轮用户的长期设定请求，不能引用工具、日志、SQL或助手文字。支持responseStyle/defaultCatalogId/defaultSessionId/sqlStyle/logLines，值均为字符串。",Map.of("action","string","values","object","sourceQuote","string"));
      var props=(ObjectNode)list.get(list.size()-1).at("/function/parameters/properties/values");props.put("additionalProperties",false);var fields=props.putObject("properties");for(String key:List.of("responseStyle","defaultCatalogId","defaultSessionId","sqlStyle","logLines"))fields.putObject(key).put("type","string");
    }
    add(list,"interaction_request","需要用户补充、选择或授权日志时提交卡片并等待；不要用于批准参数修改或重启。kind=clarification/choice/logs；questions和options均为字符串数组。",Map.of("kind","string","title","string","questions","array","options","array"));
    for(var spec:capabilities.tools()){
      var fn=list.addObject().put("type","function").putObject("function");fn.put("name",spec.path("name").asText()).put("description",spec.path("description").asText());fn.set("parameters",spec.path("inputSchema").deepCopy());
    }
    String kind=session.path("kind").asText();
    if(kind.equals("diagnosis")){
      add(list,"job_status","实时读取当前作业状态、Operator/Pod/容器/近期事件问题，不进行部署或重启。",Map.of());
      add(list,"evidence_collect","按类别重新采集当前作业证据；不可用不是零，不读任意目标。",Map.of("category","string"));
      if(access.allowed(session.path("jobId").asText(),"edit"))add(list,"repair_propose","生成待用户审阅的最小参数/SQL方案，不保存或重启；必须先取得本轮证据。",Map.of("changes","array","sql","string"));
    }else if(kind.equals("sql")){
      add(list,"sql_verification_propose","对当前已保存SQL申请短时真实执行验证，等待用户确认资源和Source/Sink影响，不直接运行。",Map.of());
      add(list,"sql_verification_cancel_propose","申请取消当前SQL的指定验证任务，等待用户确认。",Map.of("verificationId","string"));
      add(list,"requirements_propose","整理完整业务需求及必要缺口，供用户确认；不生成SQL或修改草稿。",Map.of("summary","string","unknowns","array"));
      add(list,"sql_check","按当前草稿运行环境检查候选SQL；Parser不是Planner或业务认证。",Map.of("sql","string"));
      add(list,"sql_propose","根据用户需求直接生成完整SQL候选和服务端运行配置；不要求额外需求确认，不保存或运行。imageId 可选，仅在新草稿未配置运行环境时从 runtimeImages 选择匹配用户版本的已登记镜像。",Map.of("sql","string"));
      ((ObjectNode)list.get(list.size()-1).at("/function/parameters/properties")).putObject("imageId").put("type","string");
      if(!turn.at("/context/jobId").asText().isBlank()){
        add(list,"job_status","实时查询本SQL关联作业当前是否运行，以及Operator、Pod、容器和近期事件问题；不部署。",Map.of());
        add(list,"evidence_collect","采集本 SQL 关联作业的配置、Pod/事件、Flink异常或资源指标；先取证再提出修改。",Map.of("category","string"));
        if(access.allowed(turn.at("/context/jobId").asText(),"edit"))add(list,"repair_propose","对本 SQL 已发布作业提出资源参数及可选完整SQL修复；必须先取证，审阅并确认恢复方式后才能保存/发布/启动或重启。",Map.of("changes","array","sql","string"));
      }
    }else add(list,"trace_inspect","读取指定源会话的真实轨迹并执行确定性结构评分，不回放模型或部署。",Map.of());
    if(turn.path("scene").asText().equals("status"))for(int n=list.size()-1;n>=0;n--)if(Set.of("sql_check","sql_propose","repair_propose","requirements_propose","sql_verification_propose","sql_verification_cancel_propose").contains(list.get(n).at("/function/name").asText()))list.remove(n);
    return list;
  }

  /** 验证工具名、额外字段和参数类型，再调用受信领域适配器。 */
  public Outcome execute(String name,ObjectNode args,ObjectNode session,ObjectNode turn){
    try(var trace=telemetry.start("tool."+name,"tool",Map.of("flink.tool.name",name))){trace.input(args);try{var outcome=executeTool(name,args,session,turn);var output=outcome.output().deepCopy();if(outcome.proposal()!=null)output.set("candidate",outcome.proposal());if(outcome.requirements()!=null)output.set("requirements",outcome.requirements());trace.output(output);if(outcome.output().has("error"))trace.error(outcome.output().path("error").asText());return outcome;}catch(RuntimeException ex){String code=ex instanceof BusinessException business?business.code():ex.getClass().getSimpleName();trace.error(code);trace.output(Map.of("error",code));throw ex;}}
  }
  /** 工具权限和阶段每次重新验证，观测不会扩大能力。 */
  private Outcome executeTool(String name,ObjectNode args,ObjectNode session,ObjectNode turn){
    var schema=schemas(session,turn);JsonNode found=null;
    for(var item:schema)if(name.equals(item.at("/function/name").asText()))found=item;
    if(found==null)throw new BusinessException(HttpStatus.FORBIDDEN,"HARNESS_TOOL_DENIED","当前阶段或角色未开放该工具");
    if(name.equals("preferences_propose"))return new Outcome(mapper.createObjectNode().set("interaction",preferences.propose(args,turn)),null,null,null);
    if(name.equals("sql_verification_propose")){if(!args.isEmpty())bad("验证申请不接受额外参数");return verification(session,turn,args,false);}
    if(name.equals("sql_verification_cancel_propose")){if(args.size()!=1||!args.path("verificationId").isTextual())bad("需要验证任务编号");return verification(session,turn,args,true);}
    if(name.equals("interaction_request")){
      guard.requireSafe(args.toString());var card=AiHarnessInteraction.create(args);
      if(card.path("kind").asText().equals("logs")){card.put("modelId",turn.path("modelId").asText()).put("model",turn.path("model").asText()).put("scope","本轮及回答后续接轮次中当前账号可读的脱敏日志；发送给当前所选模型。可取消或改为文字补充。");}
      return new Outcome(mapper.createObjectNode().set("interaction",card),null,null,null);
    }
    boolean extension=false;for(var spec:capabilities.tools())if(name.equals(spec.path("name").asText()))extension=true;
    if(extension)return new Outcome(capabilities.call(name,args,targetJob(session,turn),turn.path("includeLogs").asBoolean(),session.path("id").asText()),null,null,null);
    var parameters=found.at("/function/parameters/properties");
    for(var names=args.fieldNames();names.hasNext();)if(!parameters.has(names.next()))bad("工具包含未允许参数");
    for(var fields=parameters.fields();fields.hasNext();){var field=fields.next();var value=args.path(field.getKey());String type=field.getValue().path("type").asText();if(Set.of("imageId","intent").contains(field.getKey())&&value.isMissingNode())continue;
      if(type.equals("string")&&!value.isTextual()||type.equals("array")&&!value.isArray())bad("工具参数类型或必填项不正确");}
    guard.requireSafe(args.toString());
    if(name.equals("plan_update")){
      var steps=args.path("steps");if(steps.isEmpty()||steps.size()>6)bad("执行计划需为1到6个具体步骤");
      for(var step:steps)if(!step.isTextual()||step.asText().isBlank()||step.asText().length()>240)bad("计划步骤需为1到240字的文本");
      String scene=args.path("intent").asText(session.path("kind").asText().equals("sql")?"sql_development":session.path("kind").asText());
      if(!Set.of("sql_development","status","diagnosis","repair","evaluation").contains(scene))bad("执行意图无效");
      var output=mapper.createObjectNode().put("scene",scene);output.set("executionPlan",steps.deepCopy());return new Outcome(output,null,null,null);
    }
    if(name.equals("job_status"))return new Outcome(observation.status(targetJob(session,turn)),null,null,null);
    if(name.equals("context_read"))return new Outcome((ObjectNode)turn.path("context").deepCopy(),null,null,null);
    if(name.equals("memory_update")){
      String text=args.path("summary").asText();if(text.isBlank()||text.length()>4000)bad("会话记忆应为1到4000字符");
      return new Outcome(mapper.createObjectNode().put("memoryCandidate",text).put("verificationStatus","PROPOSED"),null,null,null);
    }
    if(name.equals("evidence_collect")){
      var job=jobs.get(targetJob(session,turn));requireBaseline(job.id(),turn);
      String category=args.path("category").asText();
      if(Set.of("logs","flinkLogs").contains(category)&&!turn.path("includeLogs").asBoolean())bad("本轮未授权日志取证和外发");
      if(category.equals("auto")&&!turn.path("includeLogs").asBoolean())category="pods";
      var bundle=evidence.collectCategory(job,category);
      if(!evidence.matches(jobs.get(job.id()),bundle.path("baseline")))stale();
      var safe=(ObjectNode)guard.sanitize(evidence.forModel(bundle));return new Outcome(safe,null,safe,null);
    }
    if(name.equals("repair_propose")){
      String jobId=targetJob(session,turn);access.require(jobId,"edit");requireBaseline(jobId,turn);
      if(!turn.path("evidence").isObject())bad("请先取得本轮作业证据，再生成修复方案");
      var plan=repairs.propose(jobs.get(jobId),(ObjectNode)turn.path("evidence"),args,turn.path("sequence").asLong(),access.actor());
      if(plan==null)bad("方案没有实际修改");
      return new Outcome(mapper.createObjectNode().put("status","PROPOSED").put("check",plan.path("check").asText()).put("saved",false),plan,null,null);
    }
    if(name.equals("requirements_propose")){
      String text=args.path("summary").asText();if(text.isBlank()||text.length()>8000||args.path("unknowns").size()>20)bad("需求总结或待核对项超出预算");
      for(var item:args.path("unknowns"))if(!item.isTextual()||item.asText().length()>1000)bad("待核对项须为有界文本");
      return new Outcome(mapper.createObjectNode().put("status","REQUIREMENTS_NOTED"),null,null,args.deepCopy());
    }
    if(name.equals("sql_check")||name.equals("sql_propose")){
      var draft=sqlDraft(session);checkSqlBaseline(draft,turn);
      String script=args.path("sql").asText();if(script.isBlank()||script.length()>32768)bad("SQL 候选应为1到32768字符");
      var check=sql.check(script,(ObjectNode)draft.path("runtime"));
      var report=mapper.valueToTree(check);var out=mapper.createObjectNode();out.set("check",report);
      if(!check.valid()){
        out.put("error","SQL_CHECK_FAILED").put("message",check.message());out.set("diagnostic",sqlDiagnostic(script,check.message()));
        return new Outcome(out,null,null,null);
      }
      if(name.equals("sql_check"))return new Outcome(out,null,null,null);
      repairs.validateHarnessSql(script);
      var plan=mapper.createObjectNode().put("kind","sql").put("sqlId",draft.path("id").asText()).put("sqlRevision",draft.path("revision").asLong())
          .put("sqlCreatedAt",draft.path("createdAt").asText()).put("beforeSql",draft.path("sql").asText()).put("sql",script).put("check",check.message());
      plan.put("newDraft",session.path("sqlId").asText().isBlank()).put("sqlName",draft.path("name").asText());
      prepareSqlRun(draft,plan,args.path("imageId").asText());out.set("run",plan.path("execution"));
      out.put("status","PROPOSED").put("saved",false);return new Outcome(out,plan,null,null);
    }
    return new Outcome((ObjectNode)turn.at("/context/trace").deepCopy(),null,null,null);
  }

  /** 关联目标从冻结上下文确定，不接受模型传入作业编号。 */
  private String targetJob(ObjectNode session,ObjectNode turn){return session.path("kind").asText().equals("sql")?turn.at("/context/jobId").asText():session.path("jobId").asText();}

  /** 将 Parser 的行列定位转成有界代码片段，给重试提供具体修改依据。 */
  private ObjectNode sqlDiagnostic(String script,String message){
    var out=mapper.createObjectNode().put("message",message).put("hint","根据具体行列检查标识符、逗号及引号；先修正失败片段，再检查完整脚本，不要原样重复调用");
    var match=java.util.regex.Pattern.compile("(?:第\\s*|line\\s+)([0-9]+)\\s*(?:行|[, :])",java.util.regex.Pattern.CASE_INSENSITIVE).matcher(message);
    if(match.find()){
      int line=Integer.parseInt(match.group(1));out.put("line",line);var rows=script.split("\\R",-1);var excerpt=out.putArray("excerpt");
      for(int i=Math.max(0,line-3);i<Math.min(rows.length,line+2);i++)excerpt.addObject().put("line",i+1).put("text",rows[i].length()>500?rows[i].substring(0,500):rows[i]);
    }
    return out;
  }

  /** 使用已登记运行环境生成可审阅配置；缺失运行条件不阻止交付 SQL。 */
  private void prepareSqlRun(ObjectNode draft,ObjectNode plan,String imageId){
    var runtime=(ObjectNode)draft.path("runtime").deepCopy();
    if(!imageId.isBlank()&&!runtime.isEmpty()&&!imageId.equals(runtime.path("imageId").asText()))bad("已有运行配置不能在此静默更换镜像，请在 SQL 工作台审阅配置");
    if(runtime.isEmpty()){
      if(!imageId.isBlank())runtime.put("imageId",records.require("images",imageId).path("id").asText());
      var images=records.list("images");images.sort(Comparator.comparing(i->i.path("id").asText()));
      if(!runtime.has("imageId")&&!images.isEmpty())runtime.put("imageId",images.get(0).path("id").asText());
      runtime.put("upgradeMode","stateless").put("parallelism",1).put("slots",1);
    }
    if(plan.path("newDraft").asBoolean()){
      if(!runtime.has("serviceAccount"))runtime.put("serviceAccount",properties.namespace()+"-flink");
      com.flinkweb.config.sql.DefaultJobLog.apply(runtime);
    }
    plan.set("runtime",runtime);var execution=plan.putObject("execution").put("available",false);
    if(!draft.path("jobId").asText().isBlank()){execution.put("message","已有发布作业，请在 SQL 工作台发布并选择恢复方式后启动 / 重启");return;}
    if(!runtime.path("upgradeMode").asText("stateless").equals("stateless")){execution.put("message","当前配置需要恢复状态，请在 SQL 工作台核对恢复方式后运行");return;}
    try{
      var rendered=sql.preview(new SqlDraftRequest(draft.path("name").asText(),draft.path("description").asText(),plan.path("sql").asText(),runtime,draft.path("revision").asLong()+1));
      plan.put("runYaml",rendered.path("yaml").asText());plan.put("check",rendered.path("message").asText()+"；语法检查不证明 Connector/Planner 或业务结果通过");
      var image=records.require("images",runtime.path("imageId").asText());
      plan.put("imageFingerprint",SqlDeploymentSource.hash(image.toString()));
      execution.put("available",true).put("image",image.path("repository").asText()+":"+image.path("version").asText()).put("message","创建或保存草稿、发布并提交首次无状态运行；并行度 "+runtime.path("parallelism").asInt(1)+"；共享 SQL Runner，运行受理后需手动核验");
    }catch(BusinessException failure){execution.put("message",guard.redact(failure.getMessage())+"；可先保存草稿并在 SQL 工作台补齐运行配置");}
  }

  /** 只有当前冻结的草稿与内容仍一致时，才允许继续使用本轮证据。 */
  private void requireBaseline(String jobId,ObjectNode turn){if(!evidence.matches(jobs.get(jobId),turn.at(turn.at("/context/jobBaseline").isObject()?"/context/jobBaseline":"/context/baseline")))stale();}

  /** 模型及工具步骤边界核对冻结内容，不访问集群或伪造实时观测。 */
  public void validateContext(ObjectNode session,ObjectNode turn){
    if(session.path("kind").asText().equals("diagnosis"))requireBaseline(session.path("jobId").asText(),turn);
    if(session.path("kind").asText().equals("sql")){
      var draft=sqlDraft(session);checkSqlBaseline(draft,turn);
      if(!draft.path("jobId").asText().equals(turn.at("/context/jobId").asText()))stale();
      if(!turn.at("/context/jobId").asText().isBlank())requireBaseline(targetJob(session,turn),turn);
    }
  }

  /** 未绑定时仅构造内存中的空白创作基准，批准前不保存任何 SQL 草稿。 */
  private ObjectNode sqlDraft(ObjectNode session){
    if(!session.path("sqlId").asText().isBlank())return sql.get(session.path("sqlId").asText());
    var draft=mapper.createObjectNode().put("id","").put("name","ai-sql-"+session.path("id").asText().replace("-","").substring(0,20)).put("createdAt","").put("revision",0).put("sql","");draft.putObject("runtime");return draft;
  }

  /** 手动核验原操作，同时返回真实故障和缺失证据，不推进模型或执行变更。 */
  public ObjectNode observe(String jobId,String operationId,long revision){return observation.observe(jobId,operationId,revision);}
  /** SQL 版本检查不依赖浏览器返回的旧脚本。 */
  private void checkSqlBaseline(ObjectNode draft,ObjectNode turn){if(draft.path("revision").asLong()!=turn.at("/context/baseline/sqlRevision").asLong()||!SqlDeploymentSource.hash(draft.path("sql").asText()).equals(turn.at("/context/baseline/sqlHash").asText()))stale();}
  /** 评测只检查实际保存轨迹，明确无法由轨迹证明的质量指标。 */
  private ObjectNode evaluate(String sessionId){
    var source=records.require("ai_harness_sessions",sessionId);if(!access.actor().equals(source.path("owner").asText()))deny();
    var turns=records.listForSubject("ai_harness_turns",sessionId);int calls=0,failed=0;
    for(var turn:turns){calls+=turn.path("toolCalls").asInt();failed+=turn.path("failedTools").asInt();}
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
