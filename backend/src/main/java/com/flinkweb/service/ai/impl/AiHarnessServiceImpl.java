package com.flinkweb.service.ai.impl;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.ai.AiHarnessModelClient;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.*;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.service.audit.AuditService;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.permission.*;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import jakarta.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.UnaryOperator;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.security.concurrent.DelegatingSecurityContextCallable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 单后端、有界持久化的 Harness；网络在事务外，生命周期锁先于元数据库事务。 */
@Service
public class AiHarnessServiceImpl implements AiHarnessService {
  private static final String SESSIONS="ai_harness_sessions",TURNS="ai_harness_turns",PLANS="ai_harness_plans",APPLICATIONS="ai_harness_applications";
  private static final Set<String> ACTIVE=Set.of("QUEUED","RUNNING","APPLYING");
  private final ObjectMapper mapper;
  private final RecordRepository records;
  private final AccessService access;
  private final UserService users;
  private final AiModelService models;
  private final AiHarnessModelClient modelClient;
  private final AiHarnessTools tools;
  private final AiHarnessPreferences preferences;
  private final AiContentGuard guard;
  private final AiJobState state;
  private final AiJobRepairCoordinator repairs;
  private final JobService jobs;
  private final SqlJobService sql;
  private final com.flinkweb.service.sql.SqlExecutionService executions;
  private final AiHarnessSqlCoordinator sqlApplications;
  private final AuditService audit;
  private final TransactionTemplate transaction;
  private final ThreadPoolExecutor executor=new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(6));
  private final ConcurrentMap<String,FutureTask<Void>> running=new ConcurrentHashMap<>();
  private final ConcurrentMap<String,String> actors=new ConcurrentHashMap<>();
  private final ConcurrentMap<String,Set<SseEmitter>> listeners=new ConcurrentHashMap<>();
  private final String prompt;

  /** 注入现有控制面与受控工具适配器，不启动常驻集群查询。 */
  public AiHarnessServiceImpl(ObjectMapper mapper,RecordRepository records,AccessService access,UserService users,
      AiModelService models,AiHarnessModelClient modelClient,AiHarnessTools tools,AiHarnessPreferences preferences,AiContentGuard guard,
      AiJobState state,AiJobRepairCoordinator repairs,JobService jobs,SqlJobService sql,com.flinkweb.service.sql.SqlExecutionService executions,AiHarnessSqlCoordinator sqlApplications,AuditService audit,
      org.springframework.transaction.PlatformTransactionManager manager)throws Exception{
    this.mapper=mapper;this.records=records;this.access=access;this.users=users;this.models=models;this.modelClient=modelClient;
    this.tools=tools;this.preferences=preferences;this.guard=guard;this.state=state;this.repairs=repairs;this.jobs=jobs;this.sql=sql;this.executions=executions;this.sqlApplications=sqlApplications;this.audit=audit;
    transaction=new TransactionTemplate(manager);
    prompt=new ClassPathResource("ai/harness-core.txt").getContentAsString(StandardCharsets.UTF_8);
  }

  /** 重启仅结算中断状态，不重放收费模型请求或集群写入。 */
  @PostConstruct
  public void recover(){for(var turn:records.list(TURNS))if(ACTIVE.contains(turn.path("state").asText())){
    turn.put("state",turn.has("applicationId")?"SUBMISSION_UNKNOWN":"INTERRUPTED").put("message","平台重启；保留已完成步骤，请核对原任务或原应用后继续");
    records.save(TURNS,turn.path("id").asText(),turn,turn.path("revision").asLong());
  }}

  /** 关闭连接和本地工作线程；已持久化运行身份保留供原号核对。 */
  @PreDestroy
  public void close(){executor.shutdownNow();listeners.values().forEach(set->set.forEach(SseEmitter::complete));listeners.clear();}

  /** 私有会话列表不包含正文或跨用户记录。 */
  public List<ObjectNode> list(){synchronized(state.monitor()){
    return records.list(SESSIONS).stream().filter(s->access.actor().equals(s.path("owner").asText()))
        .sorted(Comparator.comparing((ObjectNode s)->s.path("updatedAt").asText()).reversed()).map(this::sessionView).toList();
  }}

  /** 请求号即会话标识；同号不同目标拒绝复用。 */
  public ObjectNode create(ObjectNode input){synchronized(state.monitor()){
    String id=uuid(input.path("requestId").asText()),kind=input.path("kind").asText();
    if(!Set.of("diagnosis","sql","evaluation").contains(kind))bad("请选择诊断、SQL 开发或轨迹评测");
    String jobId=input.path("jobId").asText(),sqlId=input.path("sqlId").asText(),sourceId=input.path("sourceSessionId").asText();
    var old=records.find(SESSIONS,id);if(old!=null){owner(old);if(!kind.equals(old.path("kind").asText())||kind.equals("diagnosis")&&!jobId.equals(old.path("jobId").asText())||kind.equals("sql")&&!sqlId.equals(old.path("creationSqlId").asText(old.path("sqlId").asText()))||kind.equals("evaluation")&&!sourceId.equals(old.path("sourceSessionId").asText()))conflict("会话请求号已用于其他内容");return get(id);}
    if(list().size()>=20)bad("最多保留20个私有会话，请先删除不再需要的会话");
    String created,name;
    if(kind.equals("diagnosis")){access.require(jobId,"read");var job=jobs.get(jobId);created=job.createdAt().toString();name=job.name();}
    else if(kind.equals("sql")){access.require("sql:"+sqlId,"edit");if(sqlId.isBlank()){created="";name="新需求";jobId="";}else{var draft=sql.get(sqlId);created=draft.path("createdAt").asText();name=draft.path("name").asText();jobId=draft.path("jobId").asText();}}
    else {var source=load(sourceId);if(source.path("kind").asText().equals("evaluation"))bad("请选择诊断或开发会话作为评测来源");created=source.path("epoch").asText();name=source.path("title").asText();jobId="";sqlId="";}
    String title=input.path("title").asText("").strip();if(title.isEmpty())title=(kind.equals("diagnosis")?"诊断 · ":kind.equals("sql")?"开发 · ":"评测 · ")+name;
    if(title.length()>100)bad("会话标题最多100字符");guard.requireSafe(title);
    var session=mapper.createObjectNode().put("owner",access.actor()).put("epoch",UUID.randomUUID().toString()).put("kind",kind)
        .put("jobId",jobId).put("sqlId",sqlId).put("sourceSessionId",sourceId).put("subjectCreatedAt",created).put("title",title).put("sequence",0);
    session.put("creationSqlId",sqlId);records.save(SESSIONS,id,session,0);audit.record("AI_HARNESS_CREATE",id,"SUCCESS");return get(id);
  }}

  /** 获取会话不访问集群，原文上下文仅留在私有服务端记录。 */
  public ObjectNode get(String id){synchronized(state.monitor()){
    var session=load(id);var out=sessionView(session);var turns=out.putArray("turns");
    records.listForSubject(TURNS,id).stream().sorted(Comparator.comparingLong(t->t.path("sequence").asLong())).map(this::turnView).forEach(turns::add);
    if(session.has("memory"))out.set("memory",session.path("memory").deepCopy());return out;
  }}

  /** 未知写提交不能删除；其他删除同时撤销活动回调并清理所有关联集合。 */
  public void delete(String id,long revision){List<String> removed;
    synchronized(state.monitor()){
      var session=load(id);if(session.path("revision").asLong()!=revision)conflict("会话已变化，请刷新后删除");
      var targets=new ArrayList<ObjectNode>();targets.add(session);
      for(var candidate:records.list(SESSIONS))if(id.equals(candidate.path("sourceSessionId").asText())&&access.actor().equals(candidate.path("owner").asText()))targets.add(candidate);
      for(var target:targets)settled(target,"");
      removed=transaction.execute(status->{var ids=new ArrayList<String>();for(var target:targets){ids.addAll(purge(target.path("id").asText()));}audit.record("AI_HARNESS_DELETE",id,"SUCCESS");return ids;});
    }
    removed.forEach(this::interrupt);
  }

  /** 在任务受理事务中先写计划、上下文清单与不可变请求身份，再交付工作线程。 */
  public ObjectNode start(String id,ObjectNode input){synchronized(state.monitor()){
    var session=load(id);settled(session,"");String turnId=uuid(input.path("requestId").asText());
    String content=input.path("content").asText().strip(),modelId=input.path("modelId").asText();
    if(content.isBlank()||content.length()>8000)bad("消息应为1到8000字符");guard.requireSafe(content);
    String fingerprint=SqlDeploymentSource.hash(input.toString());var old=records.find(TURNS,turnId);
    if(old!=null){owner(old);if(!id.equals(old.path("sessionId").asText())||!fingerprint.equals(old.path("fingerprint").asText()))conflict("本轮请求号已用于其他内容");return turnView(old);}
    if(input.path("revision").asLong()!=session.path("revision").asLong())conflict("会话已变化，请重新加载");
    if(actors.containsKey(access.actor()))conflict("当前账号有活动 Harness 任务，请完成或取消后继续");
    var model=models.require(modelId,session.path("kind").asText().equals("sql")?"sql":"diagnosis");
    var context=tools.context(session);if(context.toString().length()>90000)bad("本轮上下文超出预算，请缩小范围");
    var turn=mapper.createObjectNode().put("sessionId",id).put("owner",access.actor()).put("epoch",session.path("epoch").asText())
        .put("sequence",session.path("sequence").asLong()+1).put("state","QUEUED").put("content",content).put("continuationGoal",content).put("reply","")
        .put("modelId",modelId).put("model",model.path("model").asText()).put("fingerprint",fingerprint).put("actorRevision",users.revision(access.actor()))
        .put("promptVersion","flink-harness-v4").put("modelRevision",model.path("revision").asLong()).put("allowExternal",model.path("external").asBoolean())
        .put("includeLogs",input.path("includeLogs").asBoolean());
    if(!input.path("templateName").asText().isBlank())turn.set("taskTemplate",tools.template(input.path("templateName").asText()));turn.set("preferences",preferences.get());turn.set("context",context);if(context.path("runtimeStatus").isObject())turn.set("runtimeStatus",context.path("runtimeStatus").deepCopy());turn.putArray("events");
    if(input.hasNonNull("requirementsTurnId")){
      var approved=requireTurn(session,input.path("requirementsTurnId").asText());
      if(!input.path("confirmed").asBoolean()||!approved.path("requirements").isObject()||!approved.at("/context/baseline").toString().equals(context.path("baseline").toString()))conflict("需求或SQL基准已变化，请重新核对并确认");
      turn.set("approvedRequirements",approved.path("requirements").deepCopy());
    }
    var manifest=turn.putObject("contextManifest").put("snapshotId",UUID.randomUUID().toString()).put("source","服务端当前草稿与共享总结")
        .put("characters",context.toString().length()).put("modelCallPolicy","不限制调用次数；可随时停止，保留请求超时与内容大小保护").put("privacy","当前用户私有会话；共享作业不公开私有消息").put("promptVersion","flink-harness-v4");
    manifest.set("baseline",context.path("baseline").deepCopy());manifest.put("hash",SqlDeploymentSource.hash(context.toString()));
    turn.putArray("plan");turn.put("planStatus","PENDING");
    addEvent(turn,"plan.preparing","等待模型制定本次执行计划",mapper.createObjectNode());
    transaction.executeWithoutResult(status->{
      for(var previous:records.listForSubject(TURNS,id))if((previous.has("planId")||previous.path("state").asText().equals("WAITING_USER"))&&!previous.has("applicationId")){
        previous.put("state","SUPERSEDED");if(previous.path("interaction").isObject())((ObjectNode)previous.path("interaction")).put("state","EXPIRED");records.save(TURNS,previous.path("id").asText(),previous,previous.path("revision").asLong());
      }
      records.save(TURNS,turnId,turn,0);if(!session.has("userGoal"))session.put("userGoal",records.listForSubject(TURNS,id).stream().min(Comparator.comparingLong(t->t.path("sequence").asLong())).map(t->t.path("content").asText()).orElse(content));records.save(SESSIONS,id,session.put("sequence",turn.path("sequence").asLong()),session.path("revision").asLong());prune(id);
    });
    String actor=access.actor();actors.put(actor,turnId);
    var future=new FutureTask<Void>(new DelegatingSecurityContextCallable<>(()->{execute(id,turnId);return null;})){
      /** 排队取消和迟到退出不能释放另一轮任务的账号资格。 */
      protected void done(){running.remove(turnId,this);actors.remove(actor,turnId);}
    };
    running.put(turnId,future);try{executor.execute(future);}catch(RejectedExecutionException error){future.cancel(true);finish(id,turnId,"FAILED","任务队列已满，请稍后重试");}
    audit.record("AI_HARNESS_START",turnId,"ACCEPTED");return turnView(records.require(TURNS,turnId));
  }}

  /** 回答卡片不占用等待线程；原请求重试返回同一个续接轮次。 */
  public ObjectNode respond(String id,String turnId,ObjectNode input){
    boolean verify;
    synchronized(state.monitor()){var session=load(id);var turn=requireTurn(session,turnId);verify=turn.at("/interaction/kind").asText().equals("verification")&&!turn.at("/interaction/state").asText().equals("ANSWERED");}
    if(verify&&input.path("decision").asText().equals("submit")&&input.path("confirmedVerification").asBoolean())submitVerification(id,turnId,input);
    return respondAnswer(id,turnId,input);
  }
  /** 网络提交在数据库事务外；意图及请求号先保存，未知结果只能原号核对。 */
  private void submitVerification(String id,String turnId,ObjectNode input){
    ObjectNode card;String requestId=uuid(input.path("requestId").asText());String fingerprint=SqlDeploymentSource.hash(input.toString());
    synchronized(state.monitor()){
      var session=load(id);var turn=requireTurn(session,turnId);card=(ObjectNode)turn.path("interaction");
      if(!turn.path("state").asText().equals("WAITING_USER")||!input.path("interactionId").asText().equals(card.path("id").asText())||session.path("sequence").asLong()!=turn.path("sequence").asLong()||Instant.parse(card.path("expiresAt").asText()).isBefore(Instant.now()))conflict("验证交互已失效");
      tools.validateContext(session,turn);AiHarnessInteraction.validateAnswer(card,input);guard.requireSafe(input.toString());
      if(card.has("verificationRequest")&&!fingerprint.equals(card.path("verificationFingerprint").asText()))conflict("存在待核对验证，请继续原请求");
      if(card.has("verificationResult"))return;
      card.put("verificationRequest",requestId).put("verificationFingerprint",fingerprint);saveTurn(turn);
    }
    ObjectNode result;
    try{result=card.path("action").asText().equals("cancel")?executions.cancel(card.path("verificationId").asText()):executions.submit(card.path("sqlId").asText(),card.path("sqlRevision").asLong(),requestId);}
    catch(RuntimeException ex){synchronized(state.monitor()){var original=records.find(TURNS,turnId);if(original!=null){var stored=(ObjectNode)original.path("interaction");var run=records.list("sql_validations").stream().filter(r->requestId.equals(r.path("requestId").asText())).findFirst();if(run.isPresent())stored.set("verificationResult",guard.sanitize(run.get()));else{stored.remove(List.of("verificationRequest","verificationFingerprint"));}saveTurn(original);}}throw ex;}
    synchronized(state.monitor()){var session=load(id);var turn=requireTurn(session,turnId);((ObjectNode)turn.path("interaction")).set("verificationResult",guard.sanitize(result));saveTurn(turn);}
  }
  /** 普通回答原子持久化并受理续接轮次，提交失败不会丢失回答入口。 */
  private ObjectNode respondAnswer(String id,String turnId,ObjectNode input){synchronized(state.monitor()){
    var session=load(id);var turn=requireTurn(session,turnId);var card=turn.path("interaction");
    if(!card.isObject()||!input.path("interactionId").asText().equals(card.path("id").asText()))conflict("交互不存在或已被替换");
    String requestId=uuid(input.path("requestId").asText()),fingerprint=SqlDeploymentSource.hash(input.toString());
    if(card.has("responseRequestId")){
      if(!requestId.equals(card.path("responseRequestId").asText())||!fingerprint.equals(card.path("responseFingerprint").asText()))conflict("该交互已提交，请继续已有任务");
      return card.path("nextTurnId").asText().isBlank()?turnView(turn):turnView(requireTurn(session,card.path("nextTurnId").asText()));
    }
    if(!turn.path("state").asText().equals("WAITING_USER")||!card.path("state").asText().equals("PENDING")||turn.path("sequence").asLong()!=session.path("sequence").asLong())conflict("旧交互已失效，请查看最新对话");
    if(Instant.parse(card.path("expiresAt").asText()).isBefore(Instant.now()))conflict("交互已过期，请重新发起任务");
    AiHarnessInteraction.validateAnswer((ObjectNode)card,input);guard.requireSafe(input.toString());
    boolean cancel=input.path("decision").asText().equals("cancel");if(card.has("verificationRequest")&&!card.has("verificationResult"))conflict("验证结果待核对，请继续原验证请求");if(!cancel)tools.validateContext(session,turn);
    var answer=(ObjectNode)card.deepCopy();answer.put("state",cancel?"CANCELED":"ANSWERED").put("responseRequestId",requestId).put("responseFingerprint",fingerprint);answer.set("answers",input.path("answers").deepCopy());
    return transaction.execute(status->{
      turn.set("interaction",answer);turn.put("state",cancel?"CANCELED":"ANSWERED");saveTurn(turn);
      if(cancel){addEvent(turn,"interaction.canceled","用户取消本次交互",mapper.createObjectNode());saveTurn(turn);return turnView(turn);}
      var next=mapper.createObjectNode().put("requestId",requestId).put("revision",session.path("revision").asLong()).put("modelId",turn.path("modelId").asText());
      StringBuilder text=new StringBuilder("补充：").append(card.path("title").asText());
      input.path("answers").fields().forEachRemaining(field->{String label=field.getKey();if(label.matches("q[0-7]"))label=card.path("questions").path(Integer.parseInt(label.substring(1))).asText();else if(label.equals("choice"))label="选择";else if(label.equals("note"))label="补充说明";text.append("\n").append(label).append("：").append(field.getValue().asText());});
      String continuation=text.toString();
      if(continuation.length()>8000)bad("续接内容过长，请缩短补充信息后重试");next.put("content",continuation);
      next.put("includeLogs",turn.path("includeLogs").asBoolean()||card.path("kind").asText().equals("logs")&&input.path("grantLogs").asBoolean());
      if(card.path("kind").asText().equals("logs")&&!turn.path("modelId").asText().equals(card.path("modelId").asText()))conflict("模型已变化，请重新确认日志范围");
      var result=start(id,next);
      var resumed=records.require(TURNS,result.path("id").asText());resumed.put("continuationGoal",turn.path("continuationGoal").asText(turn.path("content").asText()));
      var responseContext=mapper.createObjectNode();for(String field:List.of("title","kind","questions","options","answers","verificationResult"))if(answer.has(field))responseContext.set(field,answer.get(field));resumed.set("interactionResponse",responseContext);saveTurn(resumed);result=turnView(resumed);
      var original=records.require(TURNS,turnId);((ObjectNode)original.path("interaction")).put("nextTurnId",result.path("id").asText());saveTurn(original);audit.record("AI_HARNESS_RESPOND",turnId,"ANSWERED");return result;
    });
  }}

  /** 显式取消先持久化，后中断请求；迟到响应不能复活任务。 */
  public ObjectNode cancel(String id,String turnId){ObjectNode out;
    synchronized(state.monitor()){var session=load(id);var turn=requireTurn(session,turnId);
      if(turn.path("state").asText().equals("APPLYING"))conflict("执行操作已提交处理中，请核对原应用；取消对话不能撤回集群操作");
      if(ACTIVE.contains(turn.path("state").asText())||turn.path("state").asText().equals("WAITING_USER")){if(turn.path("interaction").isObject())((ObjectNode)turn.path("interaction")).put("state","CANCELED");turn.put("state","CANCELED").put("message","本轮已取消");addEvent(turn,"run.canceled","本轮已取消",mapper.createObjectNode());saveTurn(turn);}
      out=turnView(turn);
    }interrupt(turnId);return out;
  }

  /** 连接先经过归属校验，回放一个当前快照；活跃事件由实际工作线程发送。 */
  public SseEmitter events(String id,String turnId){synchronized(state.monitor()){
    var session=load(id);var turn=requireTurn(session,turnId);var set=listeners.computeIfAbsent(turnId,k->ConcurrentHashMap.newKeySet());
    if(set.size()>=4)conflict("本任务事件连接过多，请关闭重复页面");var emitter=new SseEmitter(30000L);set.add(emitter);
    Runnable remove=()->{set.remove(emitter);if(set.isEmpty())listeners.remove(turnId,set);};
    emitter.onCompletion(remove);emitter.onTimeout(()->{remove.run();emitter.complete();});emitter.onError(error->remove.run());
    try{emitter.send(SseEmitter.event().name("snapshot").data(turnView(turn)));if(!ACTIVE.contains(turn.path("state").asText()))emitter.complete();}
    catch(Exception error){remove.run();emitter.complete();}return emitter;
  }}

  /** 用户批准后只使用本轮最新候选；原运行服务负责恢复模式、身份与 dry-run。 */
  public ObjectNode apply(String id,String turnId,ObjectNode input,Principal actor){synchronized(state.monitor()){
    var session=load(id);var turn=requireTurn(session,turnId);String requestId=uuid(input.path("requestId").asText());
    if(!input.path("confirmed").asBoolean())bad("请明确确认本次方案及执行方式");settled(session,requestId);
    if(ACTIVE.contains(turn.path("state").asText()))conflict("当前任务尚未完成，不能应用候选");
    var plan=records.require(PLANS,turn.path("planId").asText());owner(plan);
    if(!id.equals(plan.path("sessionId").asText())||!turnId.equals(plan.path("turnId").asText()))conflict("方案不属于本轮");
    var existing=records.find(APPLICATIONS,requestId);
    if(existing!=null){owner(existing);if(!id.equals(existing.path("sessionId").asText())||!turnId.equals(existing.path("turnId").asText()))conflict("应用号已属于其他方案");}
    boolean original=turn.has("applicationInput")&&turn.path("applicationInput").equals(input);
    if(existing==null&&!original&&(turn.path("sequence").asLong()!=session.path("sequence").asLong()||!Set.of("AWAITING_APPROVAL","BUDGET_EXHAUSTED").contains(turn.path("state").asText())))conflict("请先完成本轮分析并审阅当前候选，旧轮次不能应用");
    if(turn.has("applicationInput")&&!turn.path("applicationInput").equals(input))conflict("本轮已绑定原申请，请按原内容继续核对");
    turn.set("applicationInput",input.deepCopy());turn.put("state","APPLYING").put("applicationId",requestId);addEvent(turn,"approval.resolved","已确认具体方案",mapper.createObjectNode().put("mode",input.path("mode").asText()));saveTurn(turn);
    try{
      ObjectNode app;
      if(plan.path("kind").asText().equals("sql"))app=sqlApplications.apply(session,plan,input,actor);
      else app=repairs.applyHarness(plan.path("jobId").asText(session.path("jobId").asText()),plan,input,actor);
      app.put("sessionId",id).put("turnId",turnId);var stored=records.require(APPLICATIONS,requestId);records.save(APPLICATIONS,requestId,stored.put("sessionId",id).put("turnId",turnId),stored.path("revision").asLong());
      turn=records.require(TURNS,turnId);turn.set("application",app);turn.put("state",applicationState(app));
      addEvent(turn,"operation.result",app.path("message").asText(),mapper.createObjectNode().put("state",app.path("state").asText()));saveTurn(turn);audit.record("AI_HARNESS_APPLY",turnId,app.path("state").asText());return turnView(turn);
    }catch(RuntimeException error){turn=records.require(TURNS,turnId);boolean noIntent=records.find(APPLICATIONS,requestId)==null;if(noIntent)turn.remove(List.of("applicationId","applicationInput"));turn.put("state",noIntent?"AWAITING_APPROVAL":"SUBMISSION_UNKNOWN").put("message",safeError(error));saveTurn(turn);throw error;}
  }}

  /** 三秒手动核对只观察原操作对应的目标，不推进模型或重复提交。 */
  public ObjectNode observe(String id,String turnId){synchronized(state.monitor()){
    var session=load(id);var turn=requireTurn(session,turnId);String applicationId=turn.path("applicationId").asText();if(applicationId.isBlank())bad("本轮没有需要核对的应用");
    long now=System.currentTimeMillis();if(now-turn.path("lastObserveAt").asLong()<3000)conflict("请间隔三秒后手动刷新");
    turn.put("lastObserveAt",now);saveTurn(turn);
    var raw=records.find(APPLICATIONS,applicationId);
    if(raw==null){turn=records.require(TURNS,turnId);turn.put("state","SUBMISSION_UNKNOWN").put("message","尚无已提交的应用记录；请继续原申请，由原请求号核对并完成剩余检查");saveTurn(turn);return turnView(turn);}
    owner(raw);ObjectNode app;
    if(raw.path("jobId").asText().isBlank())app=raw.deepCopy();else app=repairs.harnessApplication(raw.path("jobId").asText(),applicationId);
    turn=records.require(TURNS,turnId);turn.set("application",app);String status=app.path("state").asText();
    if(app.hasNonNull("operationId")&&!raw.path("jobId").asText().isBlank()){
      var result=tools.observe(raw.path("jobId").asText(),app.path("operationId").asText(),raw.path("savedJobRevision").asLong());turn.set("observation",result);
      turn.put("state",status.equals("ACCEPTED")?(result.path("runningRevisionConfirmed").asBoolean()?"RUNNING_OBSERVED":"WAITING_OBSERVATION"):applicationState(app));
      addEvent(turn,"verification.completed","已按原操作手动核验；业务结果另需验证",result);
    }else turn.put("state",applicationState(app));
    saveTurn(turn);return turnView(turn);
  }}

  /** 领域删除在同一生命周期锁中级联清理所有用户的关联会话。 */
  @EventListener
  public void jobDeleted(AiJobState.Purged event){removeSubject("jobId",event.jobId());}
  /** 未发布 SQL 删除也清理独立开发会话，不留下源引用。 */
  @EventListener
  public void sqlDeleted(AiJobState.SqlDeleted event){removeSubject("sqlId",event.sqlId());}

  /** 模型与工具按目标持续推进，取消和内容大小保护独立于调用次数。 */
  private void execute(String id,String turnId){try{
    var initial=active(id,turnId);update(id,turnId,t->t.put("state","RUNNING"));
    var session=loadLocked(id);var model=models.require(initial.path("modelId").asText(),session.path("kind").asText().equals("sql")?"sql":"diagnosis");
    var messages=mapper.createArrayNode();messages.addObject().put("role","system").put("content",prompt);
    var history=records.listForSubject(TURNS,id).stream().filter(t->t.path("sequence").asLong()<initial.path("sequence").asLong()).sorted(Comparator.comparingLong(t->t.path("sequence").asLong())).toList();
    for(var previous:history.subList(Math.max(0,history.size()-4),history.size())){messages.addObject().put("role","user").put("content",previous.path("content").asText());if(!previous.path("reply").asText().isBlank())messages.addObject().put("role","assistant").put("content",previous.path("reply").asText());}
    var request=mapper.createObjectNode();request.set("context",initial.path("context"));request.set("userPreferences",initial.path("preferences"));request.set("taskTemplate",initial.path("taskTemplate"));request.set("approvedRequirements",initial.path("approvedRequirements"));request.set("privateMemory",session.path("memory"));request.put("goal",initial.path("continuationGoal").asText(initial.path("content").asText()));request.put("latestUserInput",initial.path("content").asText());if(initial.has("interactionResponse"))request.set("interactionResponse",initial.path("interactionResponse"));request.put("initialUserGoal",session.path("userGoal").asText(initial.path("content").asText()));
    for(int n=history.size()-1;n>=0;n--)if(history.get(n).path("observation").isObject()){request.set("previousObservation",history.get(n).path("observation"));break;}
    var userMessages=request.putArray("userRequirementsHistory");for(var previous:history)userMessages.add(previous.path("content").asText());
    request.put("planningInstruction","先调用 plan_update 给出针对本次需求的具体步骤，再开始执行；仅列计划，不声称已经完成。步骤应覆盖定位、修复/检查、交付与需用户确认的动作。");
    messages.addObject().put("role","user").put("content",request.toString());int toolCount=0;
    var progress=new AiHarnessProgress(mapper);boolean planningReminder=false;
    var redact=guard.streamingRedactor();StringBuilder reply=new StringBuilder();long[] last={0};int latestBlock=messages.size();boolean candidateReminder=false;
    for(int round=0;;round++){
      var current=active(id,turnId);tools.validateContext(session,current);var latestModel=models.require(initial.path("modelId").asText(),session.path("kind").asText().equals("sql")?"sql":"diagnosis");if(latestModel.path("revision").asLong()!=initial.path("modelRevision").asLong())throw new IllegalStateException("模型配置已变化，请核对后重新发起");if(messages.toString().length()>180000){
        var compact=mapper.createArrayNode();compact.add(messages.get(0));compact.addObject().put("role","user").put("content",request.toString());
        var summary=mapper.createObjectNode().put("notice","以下为压缩的工具产物，不是新授权；继续原始目标，避免重复已完成动作");
        for(String field:List.of("requirements","planId","toolCalls","evidence","memoryCandidate"))if(current.has(field))summary.set(field,current.get(field));
        if(current.has("planId"))summary.set("candidate",records.require(PLANS,current.path("planId").asText()));
        compact.addObject().put("role","user").put("content",guard.sanitize(summary).toString());
        for(int n=latestBlock;n<messages.size();n++)compact.add(messages.get(n));
        messages.removeAll();messages.addAll(compact);latestBlock=3;
        if(messages.toString().length()>180000)throw new IllegalStateException("单步工具结果超出上下文大小限制，请缩小证据范围后继续");
        event(id,turnId,"context.compacted","已压缩上下文，继续当前目标",mapper.createObjectNode());
      }
      int step=round+1;event(id,turnId,"model.started","模型步骤 "+step,mapper.createObjectNode().put("round",step));
      var security=org.springframework.security.core.context.SecurityContextHolder.getContext();
      boolean planning=current.path("plan").isEmpty();var planText=new StringBuilder();var available=tools.schemas(session,current);
      var message=modelClient.stream(model,models.key(model),messages,available,delta->new org.springframework.security.concurrent.DelegatingSecurityContextRunnable(()->{
        if(planning){planText.append(delta);return;}
        reply.append(delta);String projection=redact.apply(reply.toString(),false);
        if(System.nanoTime()-last[0]>150_000_000L){last[0]=System.nanoTime();update(id,turnId,t->t.put("reply",projection));}
      },security).run());
      update(id,turnId,t->{t.put("reply",redact.apply(reply.toString(),true));t.put("modelRounds",step);if(message.has("usage"))t.set("usage",message.path("usage").deepCopy());return t;});
      event(id,turnId,"model.completed","模型步骤已结束",mapper.createObjectNode().put("streamed",message.path("streamed").asBoolean()));
      message.remove(List.of("streamed","usage"));latestBlock=messages.size();messages.add(message);
      if(message.path("tool_calls").isEmpty()){
        if(planning){
          var steps=modelPlan(message.path("content").asText(planText.toString()));
          if(!steps.isEmpty()){
            update(id,turnId,t->{t.set("plan",steps);t.put("planStatus","READY");addEvent(t,"plan.updated","模型已制定执行计划",mapper.createObjectNode());return t;});
            messages.addObject().put("role","user").put("content","执行计划已展示，现在按计划使用当前可用工具推进目标。");continue;
          }
          if(planningReminder){finish(id,turnId,"FAILED","模型未提供有效执行计划，已停止；请更换模型或补充明确目标后重试。");return;}
          planningReminder=true;messages.addObject().put("role","user").put("content","请先调用 plan_update，steps 为1到6个针对本次目标的具体步骤；不要跳过计划或声称已经执行。");continue;
        }
        if(reply.toString().isBlank())throw new IllegalStateException("模型未返回有效回复");
        var finished=active(id,turnId);tools.validateContext(session,finished);
        if(session.path("kind").asText().equals("sql")&&!Set.of("status","diagnosis").contains(finished.path("scene").asText())&&!finished.has("planId")&&!candidateReminder&&(reply.toString().contains("```sql")||finished.path("sqlChecked").asBoolean())){
          candidateReminder=true;messages.addObject().put("role","user").put("content","平台检查：尚无已登记的 SQL 候选，当前文字和 sql_check 不能创建草稿，也不会出现创建并运行入口。请调用 sql_propose 交付完整 SQL 候选；若确有必要缺口，只说明具体阻塞，勿重复已回答问题或声称已创建。");continue;
        }
        if(session.path("kind").asText().equals("sql")&&!Set.of("status","diagnosis").contains(finished.path("scene").asText())&&!finished.has("planId")&&(reply.toString().contains("```sql")||finished.path("sqlChecked").asBoolean())){
          finish(id,turnId,"FAILED","模型未登记可应用候选，尚未创建 SQL；请重试生成候选，或复制脚本到 SQL 工作台。");return;
        }
        finish(id,turnId,"SUCCEEDED","");return;
      }
      for(var call:message.path("tool_calls")){
        ++toolCount;
        current=active(id,turnId);final int executedCalls=toolCount;update(id,turnId,t->t.put("toolCalls",executedCalls));String name=call.at("/function/name").asText();ObjectNode output;ObjectNode arguments=mapper.createObjectNode();
        try{
          boolean offered=false;for(var schema:available)if(name.equals(schema.at("/function/name").asText()))offered=true;
          if(!offered)throw new BusinessException(HttpStatus.FORBIDDEN,"HARNESS_TOOL_DENIED","模型请求了本步骤未开放的工具；先完成执行计划，再使用提供的工具");
          var parsed=mapper.readTree(call.at("/function/arguments").asText());if(!(parsed instanceof ObjectNode args))throw new IllegalArgumentException("工具参数必须为对象");arguments=args;
          guard.requireSafe(args.toString());event(id,turnId,"tool.started",toolTitle(name),mapper.createObjectNode().put("tool",name).set("input",guard.sanitize(args)));
          var outcome=tools.execute(name,args,session,current);active(id,turnId);tools.validateContext(session,current);output=outcome.output();
          synchronized(state.monitor()){
            var turn=active(id,turnId);if(output.path("executionPlan").isArray()){
              turn.set("plan",output.path("executionPlan").deepCopy());turn.put("planStatus","READY");if(output.has("scene"))turn.put("scene",output.path("scene").asText());addEvent(turn,"plan.updated","模型已制定执行计划",mapper.createObjectNode());
            }if(name.equals("job_status")){turn.set("runtimeStatus",output.deepCopy());if(!Set.of("diagnosis","repair").contains(turn.path("scene").asText()))turn.put("scene","status");}
            if(name.equals("evidence_collect")&&!turn.path("scene").asText().equals("repair"))turn.put("scene","diagnosis");
            if(Set.of("sql_check","sql_propose").contains(name)&&turn.path("scene").asText().isBlank())turn.put("scene","sql_development");
            if(name.equals("trace_inspect"))turn.put("scene","evaluation");
            if(name.equals("repair_propose"))turn.put("scene","repair");
            if(name.equals("sql_check")&&output.at("/check/valid").asBoolean())turn.put("sqlChecked",true);if(outcome.evidence()!=null)turn.set("evidence",outcome.evidence());
            if(outcome.requirements()!=null)turn.set("requirements",outcome.requirements());
            if(outcome.proposal()!=null){
              var plan=outcome.proposal().put("sessionId",id).put("turnId",turnId).put("owner",access.actor());
              var previousPlan=records.find(PLANS,turn.path("planId").asText());var saved=records.save(PLANS,previousPlan==null?UUID.randomUUID().toString():previousPlan.path("id").asText(),plan,previousPlan==null?0:previousPlan.path("revision").asLong());turn.put("planId",saved.path("id").asText());output.put("planId",saved.path("id").asText());
            }
            if(output.path("interaction").isObject())turn.set("interaction",output.path("interaction").deepCopy());
            if(output.has("memoryCandidate"))turn.putObject("memoryCandidate").put("summary",output.path("memoryCandidate").asText()).put("verificationStatus","PROPOSED");
            turn.put("toolCalls",toolCount);saveTurn(turn);
          }
          var visible=output.deepCopy();if(visible.toString().length()>24000)visible=mapper.createObjectNode().put("note","完整结果超出轨迹展示预算，模型仍取得受限原结果").put("tool",name);
          event(id,turnId,"tool.completed",toolTitle(name)+"完成",mapper.createObjectNode().put("tool",name).set("output",guard.sanitize(visible)));
        }catch(BusinessException error){
          if(Set.of("HARNESS_CONTEXT_CHANGED","AI_PLAN_STALE").contains(error.code()))throw error;
          output=mapper.createObjectNode().put("error",error.code()).put("message",error.getMessage());event(id,turnId,"tool.failed",safeError(error),mapper.createObjectNode().put("tool",name));
        }
        if(output.path("interaction").isObject()){finish(id,turnId,"WAITING_USER","请在下方选择或补充，提交后继续原任务");return;}
        var recovery=progress.record(name,arguments,output);
        if(output.has("error"))update(id,turnId,t->t.put("failedTools",t.path("failedTools").asInt()+1));
        if(!recovery.path("action").asText().equals("continue")){
          var visibleRecovery=(ObjectNode)guard.sanitize(recovery);if(output.path("diagnostic").isObject())visibleRecovery.set("diagnostic",guard.sanitize(output.path("diagnostic")));
          update(id,turnId,t->{t.set("recovery",visibleRecovery);return t;});
          event(id,turnId,"recovery."+recovery.path("action").asText(),recovery.path("message").asText(),visibleRecovery);
          if(recovery.path("action").asText().equals("stop")){finish(id,turnId,"STALLED",recovery.path("message").asText());return;}
          output.set("harnessRecovery",visibleRecovery);
        }
        if(output.toString().length()>90000)throw new IllegalStateException("工具输出超过本轮上下文预算");
        messages.addObject().put("role","tool").put("tool_call_id",call.path("id").asText()).put("content",guard.sanitize(output).toString());
      }
    }
  }catch(Exception error){if(error instanceof InterruptedException)Thread.currentThread().interrupt();finish(id,turnId,Thread.currentThread().isInterrupted()?"CANCELED":"FAILED",safeError(error));}}

  /** 兼容模型以项目符号输出计划，仍只接受模型产生的短步骤，不提供固定兜底计划。 */
  private ArrayNode modelPlan(String content){
    var result=mapper.createArrayNode();
    for(String line:content.split("\\R"))if(line.matches("^\\s*(?:[-*]|[0-9]+[.)、])\\s*.+")){
      String step=guard.redact(line.replaceFirst("^\\s*(?:[-*]|[0-9]+[.)、])\\s*",""));
      if(step.isBlank()||step.length()>240||result.size()>=6)return mapper.createArrayNode();result.add(step);
    }
    return result;
  }

  /** 当前身份、生命周期和取消状态每次外发及工具动作前重新核对。 */
  private ObjectNode active(String id,String turnId){synchronized(state.monitor()){
    var session=load(id);var turn=requireTurn(session,turnId);
    if(Thread.currentThread().isInterrupted()||!Set.of("QUEUED","RUNNING").contains(turn.path("state").asText())||users.revision(access.actor())!=turn.path("actorRevision").asLong())throw new CancellationException("任务已取消或身份已变化");
    return turn;
  }}
  /** 以当前锁保护读取，避免删除后重新创建会话。 */
  private ObjectNode loadLocked(String id){synchronized(state.monitor()){return load(id);}}
  /** 只返回当前用户持有且目标仍为同一生命周期的会话。 */
  private ObjectNode load(String id){var session=records.require(SESSIONS,id);owner(session);
    String kind=session.path("kind").asText();if(kind.equals("diagnosis")){var job=jobs.get(session.path("jobId").asText());if(!job.createdAt().toString().equals(session.path("subjectCreatedAt").asText()))conflict("作业生命周期已变化");}
    if(kind.equals("sql")){access.require("sql:"+session.path("sqlId").asText(),"edit");var draft=session.path("sqlId").asText().isBlank()?mapper.createObjectNode().put("createdAt",""):sql.get(session.path("sqlId").asText());if(!draft.path("createdAt").asText().equals(session.path("subjectCreatedAt").asText()))conflict("SQL 生命周期已变化");session.put("jobId",draft.path("jobId").asText());}return session;}
  /** 轮次同时绑定会话、创建者和不可复用的生命周期令牌。 */
  private ObjectNode requireTurn(ObjectNode session,String turnId){var turn=records.require(TURNS,turnId);owner(turn);if(!session.path("id").asText().equals(turn.path("sessionId").asText())||!session.path("epoch").asText().equals(turn.path("epoch").asText()))conflict("任务不属于当前会话生命周期");return turn;}
  /** 使用最新记录更新流式快照，客户端替换投影而非重放文本。 */
  private void update(String id,String turnId,UnaryOperator<ObjectNode> change){ObjectNode view;
    synchronized(state.monitor()){var turn=active(id,turnId);view=turnView(saveTurn(change.apply(turn)));}publish(turnId,view);
  }
  /** 事件只由真实控制流产生，不接收模型伪造调用。 */
  private void event(String id,String turnId,String type,String title,ObjectNode detail){update(id,turnId,t->{addEvent(t,type,title,detail);return t;});}
  /** 持久化有界执行事件，文本增量不逐 Token 写事件表。 */
  private void addEvent(ObjectNode turn,String type,String title,ObjectNode detail){var events=turn.withArray("events");long sequence=turn.path("eventSequence").asLong(events.size())+1;turn.put("eventSequence",sequence);if(events.size()>=100){events.remove(0);turn.put("eventsTruncated",true);}events.addObject().put("sequence",sequence).put("type",type).put("title",title).put("at",Instant.now().toString()).set("detail",detail);}
  /** 完成只修改仍存在且活动的原任务；删除和取消后的迟到结果被丢弃。 */
  private void finish(String id,String turnId,String terminal,String message){ObjectNode visible=null;
    synchronized(state.monitor()){
      var session=records.find(SESSIONS,id);var turn=records.find(TURNS,turnId);if(session==null||turn==null||!session.path("epoch").asText().equals(turn.path("epoch").asText())||!Set.of("QUEUED","RUNNING").contains(turn.path("state").asText()))return;
      if(terminal.equals("SUCCEEDED")&&turn.has("planId"))terminal="AWAITING_APPROVAL";
      turn.put("state",terminal).put("message",message);addEvent(turn,"run.completed",message.isBlank()?"本轮分析完成":message,mapper.createObjectNode().put("state",terminal));
      transaction.executeWithoutResult(status->{saveTurn(turn);if(turn.has("memoryCandidate")){session.set("memory",turn.path("memoryCandidate").deepCopy());session.withObject("/memory").put("sourceTurnId",turnId);}records.save(SESSIONS,id,session,session.path("revision").asLong());});visible=turnView(turn);
    }if(terminal.equals("WAITING_USER"))actors.remove(access.actor(),turnId);publish(turnId,visible);
  }
  /** 会话上限与轮次保留规则公开，保留全部活动和待核对写入。 */
  private void prune(String id){var turns=records.listForSubject(TURNS,id);turns.sort(Comparator.comparingLong(t->t.path("sequence").asLong()));while(turns.size()>10){var oldest=turns.remove(0);if(ACTIVE.contains(oldest.path("state").asText()))conflict("旧任务仍在执行，请先核对");String turnId=oldest.path("id").asText();for(String collection:List.of(PLANS,APPLICATIONS))for(var row:records.listForSubject(collection,id))if(turnId.equals(row.path("turnId").asText()))records.delete(collection,row.path("id").asText(),row.path("revision").asLong());records.delete(TURNS,turnId,oldest.path("revision").asLong());}}
  /** 原应用未结算时保护恢复入口，同一原号重试不被阻止。 */
  private void settled(ObjectNode session,String except){
    for(var turn:records.listForSubject(TURNS,session.path("id").asText()))if(turn.has("applicationId")&&!except.equals(turn.path("applicationId").asText())&&records.find(APPLICATIONS,turn.path("applicationId").asText())==null)conflict("存在尚未核对的原申请，请先核对后继续或删除会话");
    for(var turn:records.listForSubject(TURNS,session.path("id").asText()))if(turn.at("/interaction/verificationRequest").isTextual()&&!turn.at("/interaction/verificationResult").isObject())conflict("存在待核对SQL验证，请继续原请求");
    var applications=new ArrayList<ObjectNode>(records.list(APPLICATIONS));applications.addAll(records.list("ai_job_applications"));
    for(var app:applications){
    boolean target=session.path("id").asText().equals(app.path("sessionId").asText())||!session.path("jobId").asText().isBlank()&&session.path("jobId").asText().equals(app.path("jobId").asText());
    if(!target||except.equals(app.path("id").asText()))continue;String status=app.path("state").asText();var operation=records.find("operations",app.path("operationId").asText());if(operation!=null)status=operation.path("state").asText();
    if(Set.of("SAVING","SUBMITTING","UNKNOWN").contains(status)||status.equals("SAVED")&&Set.of("restart","run").contains(app.path("mode").asText()))conflict("存在尚未核对的运行提交，请先核对原操作后继续或删除会话");
  }}
  /** 领域删除不保留孤立会话；活动调用取消后也不能恢复已删除记录。 */
  private void removeSubject(String field,String value){synchronized(state.monitor()){
    var ids=new HashSet<String>();for(var session:records.list(SESSIONS))if(value.equals(session.path(field).asText()))ids.add(session.path("id").asText());
    for(var session:records.list(SESSIONS))if(ids.contains(session.path("sourceSessionId").asText()))ids.add(session.path("id").asText());
    for(String id:ids)purge(id).forEach(this::interrupt);
    if(field.equals("jobId"))for(var row:records.list("ai_harness_log_snapshots"))if(value.equals(row.path("jobId").asText()))records.delete("ai_harness_log_snapshots",row.path("id").asText(),row.path("revision").asLong());
  }}
  /** 关联集合通过会话主体索引清理，不影响共享总结、作业草稿或运行审计。 */
  private List<String> purge(String id){var turns=records.listForSubject(TURNS,id);for(var row:records.list("ai_harness_log_snapshots"))if(id.equals(row.path("sessionId").asText()))records.delete("ai_harness_log_snapshots",row.path("id").asText(),row.path("revision").asLong());for(String collection:List.of(TURNS,PLANS,APPLICATIONS))for(var row:records.listForSubject(collection,id))records.delete(collection,row.path("id").asText(),row.path("revision").asLong());var session=records.find(SESSIONS,id);if(session!=null)records.delete(SESSIONS,id,session.path("revision").asLong());return turns.stream().map(t->t.path("id").asText()).toList();}
  /** 中断句柄与 SSE 连接仅在进程内存在，不作为恢复事实来源。 */
  private void interrupt(String turnId){var future=running.get(turnId);if(future!=null){future.cancel(true);executor.remove(future);}var set=listeners.remove(turnId);if(set!=null)set.forEach(SseEmitter::complete);}
  /** 写入后返回带修订的原任务，保存不隐式创建已删除任务。 */
  private ObjectNode saveTurn(ObjectNode turn){var saved=records.save(TURNS,turn.path("id").asText(),turn,turn.path("revision").asLong());turn.put("revision",saved.path("revision").asLong());return saved;}
  /** 页面不读取内部生命周期令牌、原始上下文或幂等指纹。 */
  private ObjectNode sessionView(ObjectNode value){return value.deepCopy().without(List.of("epoch","memory"));}
  /** 每次读取重新脱敏；仅返回自己的产物和应用投影。 */
  private ObjectNode turnView(ObjectNode value){ObjectNode out=value.deepCopy();out.remove(List.of("context","evidence","fingerprint","epoch","actorRevision","memoryCandidate","applicationInput","preferences"));
    var plan=records.find(PLANS,value.path("planId").asText());if(plan!=null)out.set("candidate",plan.deepCopy());
    var app=records.find(APPLICATIONS,value.path("applicationId").asText());if(app!=null&&value.path("sessionId").asText().equals(app.path("sessionId").asText())&&value.path("id").asText().equals(app.path("turnId").asText())){if(!out.path("application").isObject())out.set("application",app.deepCopy());else if(app.has("request"))((ObjectNode)out.path("application")).set("request",app.path("request").deepCopy());}
    if(value.has("applicationInput")){if(!out.path("application").isObject())out.putObject("application").put("state","UNKNOWN").put("message","原申请已登记，待核对");((ObjectNode)out.path("application")).set("request",value.path("applicationInput").deepCopy());}
    if(out.at("/interaction/state").asText().equals("PENDING")&&Instant.parse(out.at("/interaction/expiresAt").asText()).isBefore(Instant.now()))((ObjectNode)out.path("interaction")).put("state","EXPIRED");
    if(out.path("interaction").isObject())((ObjectNode)out.path("interaction")).remove(List.of("responseFingerprint","verificationFingerprint"));
    if(out.path("application").isObject())((ObjectNode)out.path("application")).remove("fingerprint");return (ObjectNode)guard.sanitize(out);}
  /** 应用失败与未结算状态不能投影为保存成功。 */
  private String applicationState(ObjectNode app){return switch(app.path("state").asText()){case "ACCEPTED"->"WAITING_OBSERVATION";case "SAVED"->Set.of("restart","run").contains(app.path("mode").asText())?"SUBMISSION_UNKNOWN":"APPLIED";case "FAILED","REJECTED"->"FAILED";default->"SUBMISSION_UNKNOWN";};}
  /** 慢客户端断开不取消已经授权的后台任务。 */
  private void publish(String turnId,ObjectNode view){if(view==null)return;var set=listeners.get(turnId);if(set==null)return;for(var emitter:set)try{emitter.send(SseEmitter.event().name("snapshot").data(view));if(!ACTIVE.contains(view.path("state").asText()))emitter.complete();}catch(Exception error){set.remove(emitter);emitter.complete();}}
  /** 私有会话和任务归属不因管理员或共享作业而放宽。 */
  private void owner(ObjectNode value){if(!access.actor().equals(value.path("owner").asText()))throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权访问其他用户的私有会话");}
  /** 请求号严格限制为 UUID，防止异常键或路径进入记录。 */
  private String uuid(String value){try{if(!UUID.fromString(value).toString().equals(value))bad("请求标识无效");return value;}catch(IllegalArgumentException error){bad("请求标识无效");return "";}}
  /** 分类错误只返回可读原因，不回显上游或敏感正文。 */
  private String safeError(Throwable error){if(error instanceof BusinessException business)return guard.redact(business.getMessage());if(error instanceof CancellationException||error instanceof InterruptedException)return "任务已取消或身份已变化";return "本轮未完成："+(error instanceof IllegalStateException?guard.redact(error.getMessage()==null?"协议或状态异常":error.getMessage()):"模型或工具请求失败，请核对原任务后重试");}
  /** 可读工具名用于对话过程，不把技术堆栈放入正文。 */
  private String toolTitle(String name){return switch(name){case "plan_update"->"制定本次执行计划";case "job_status"->"查询当前作业状态与问题";case "context_read"->"读取本轮上下文";case "evidence_collect"->"采集分类证据";case "repair_propose"->"检查修复候选";case "requirements_propose"->"整理需求确认单";case "sql_check"->"检查 SQL";case "sql_propose"->"生成 SQL 候选";case "memory_update"->"整理会话记忆";case "trace_inspect"->"评测实际轨迹";default->"核对工具请求";};}
  /** 输入不合法不进入外发或执行。 */
  private void bad(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_INVALID_REQUEST",message);}
  /** 冲突保持原身份供核对，不隐式另建任务。 */
  private void conflict(String message){throw new BusinessException(HttpStatus.CONFLICT,"HARNESS_CONFLICT",message);}
}
