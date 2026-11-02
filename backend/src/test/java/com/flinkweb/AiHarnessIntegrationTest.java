package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.ai.AiHarnessModelClient;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.*;
import com.flinkweb.service.ai.support.*;
import com.flinkweb.service.job.creation.*;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.sql.SqlJobService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 真实元数据与领域服务回归，模型和集群使用可控替身，不操作用户作业。 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username="admin",roles="ADMIN")
class AiHarnessIntegrationTest {
  static final Path DATABASE;
  static {try{DATABASE=Files.createTempDirectory("flink-harness-").resolve("test.db");}catch(Exception error){throw new RuntimeException(error);}}
  @Autowired ObjectMapper mapper;
  @Autowired RecordRepository records;
  @Autowired AiHarnessService harness;
  @Autowired AiHarnessCapabilities capabilities;
  @Autowired AiHarnessPreferences preferences;
  @Autowired AiHarnessMcpService mcp;
  @MockitoBean com.flinkweb.client.flink.AiFlinkEvidenceClient evidenceClient;
  @MockitoBean com.flinkweb.service.sql.SqlExecutionService executions;
  @Autowired AiModelService models;
  @Autowired JobService jobs;
  @Autowired YamlService yaml;
  @Autowired SqlJobService sql;
  @Autowired AiJobState state;
  @Autowired AiHarnessTools tools;
  @Autowired AiJobRepairCoordinator repairs;
  @Autowired org.springframework.context.ApplicationEventPublisher events;
  @Autowired MockMvc mvc;
  @MockitoBean AiHarnessModelClient model;
  @MockitoBean KubernetesClient kubernetes;
  @MockitoBean DiagnosticService diagnostics;
  @MockitoBean S3StorageClient storage;
  JobDraft job;
  ObjectNode live;
  String modelId;

  /** 合成测试凭据仅作用于独立临时数据库。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry properties){
    properties.add("platform.storage.type",()->"local");properties.add("platform.storage.local-path",()->DATABASE.toString());properties.add("platform.storage.initialize",()->true);
    properties.add("platform.artifacts.credentials-secret",()->"test-s3");properties.add("platform.namespace",()->"flink-jobs");properties.add("platform.security.password",()->"Test-password-12345");properties.add("platform.security.master-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
  }

  /** 每例隔离模型、作业及真实草稿修订，GET 替身不产生后台观测。 */
  @BeforeEach
  void setup()throws Exception{
    for(var row:harness.list())harness.delete(row.path("id").asText(),row.path("revision").asLong());
    when(storage.jobPrefix(anyString())).thenAnswer(call->"artifacts/jobs/"+call.getArgument(0)+"/");
    String name="harness-"+UUID.randomUUID().toString().substring(0,8);
    String source="apiVersion: flink.apache.org/v1beta1\nkind: FlinkDeployment\nmetadata:\n  name: "+name+"\nspec:\n  image: flink:1.20.5\n  flinkVersion: v1_20\n  serviceAccount: flink-jobs-flink\n  taskManager:\n    resource:\n      memory: 1024m\n      cpu: 1\n  job:\n    jarURI: local:///opt/flink/examples/streaming/StateMachineExample.jar\n    parallelism: 1\n    upgradeMode: stateless\n";
    job=jobs.create(new DraftRequest(name,"","1.20.5",source,0));live=yaml.parse(source);
    live.withObject("/metadata").put("name",job.deploymentName()).put("namespace","flink-jobs").put("uid","uid-one").put("resourceVersion","10").put("generation",2);
    live.withObject("/metadata/labels").put("flink-web/job-id",job.id());live.withObject("/status/jobStatus").put("state","FAILED");
    when(kubernetes.base()).thenReturn("/deployments");when(kubernetes.call(anyString(),anyString(),any())).thenAnswer(call->call.getArgument(0).equals("GET")?live.deepCopy():call.getArgument(2));
    when(diagnostics.inspect(job.id())).thenReturn(mapper.createObjectNode().put("error","memory pressure"));
    when(diagnostics.redactEvidence(eq(job.id()),any())).thenAnswer(call->call.getArgument(1));
    modelId="harness-model-"+UUID.randomUUID();var config=mapper.createObjectNode().put("name","测试工具模型").put("model","test").put("baseUrl","https://example.com/v1").put("enabled",true).put("external",false);
    config.putArray("purposes").add("diagnosis").add("sql");models.save(modelId,config);
    doAnswer(call->{var planning=planning(call);if(planning!=null)return planning;call.getArgument(4,Consumer.class).accept("检查完成，尚未实施修改。");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
  }
  /** 构造不含内部推理的最终消息。 */
  private ObjectNode answer(){return mapper.createObjectNode().put("role","assistant").put("content","检查完成，尚未实施修改。").put("streamed",true);}
  /** 原生工具消息由实际运行环消费，不伪造已执行事件。 */
  private ObjectNode call(String name,ObjectNode args){var out=answer();out.put("content","");var item=out.putArray("tool_calls").addObject().put("id","call_"+UUID.randomUUID()).put("type","function");item.putObject("function").put("name",name).put("arguments",args.toString());return out;}
  /** 模型替身先返回动态计划，执行阶段计数不混入计划请求。 */
  private ObjectNode planning(org.mockito.invocation.InvocationOnMock invocation)throws Exception{
    var schemas=invocation.getArgument(3,ArrayNode.class);if(schemas.size()!=1||!schemas.get(0).at("/function/name").asText().equals("plan_update"))return null;
    var messages=invocation.getArgument(2,ArrayNode.class);String profile=mapper.readTree(messages.get(messages.size()-1).path("content").asText()).at("/context/profile").asText();
    var args=mapper.createObjectNode().put("intent",profile.equals("sql")?"sql_development":profile);
    args.putArray("steps").add("模型计划：读取本次目标的当前上下文").add("模型计划：使用相应证据和检查工具").add("模型计划：交付结论与可审阅修改");return call("plan_update",args);
  }

  /** 创建诊断会话不访问模型或集群。 */
  private ObjectNode session(){return harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","diagnosis").put("jobId",job.id()));}
  /** 冻结当前修订和请求身份。 */
  private ObjectNode input(ObjectNode session){return mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("revision",session.path("revision").asLong()).put("modelId",modelId).put("content","请检查并给出最小方案");}
  /** 等待实际有界后台执行结束，不启动业务刷新。 */
  private ObjectNode completed(String sessionId,String turnId){
    org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(30)).until(()->!Set.of("QUEUED","RUNNING").contains(records.require("ai_harness_turns",turnId).path("state").asText()));
    for(var turn:harness.get(sessionId).path("turns"))if(turn.path("id").asText().equals(turnId))return (ObjectNode)turn;throw new AssertionError("缺少任务");
  }
  /** 工具只生成候选，明确确认后保存一次，原请求重复不再次修改。 */
  @Test
  void nativeToolsProposeAndConfirmedSaveIsIdempotent()throws Exception{
    var counter=new AtomicInteger();doAnswer(invocation->{var planning=planning(invocation);if(planning!=null)return planning;
      assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return switch(counter.getAndIncrement()){
        case 0->call("evidence_collect",mapper.createObjectNode().put("category","pods"));
        case 1->{var args=mapper.createObjectNode().put("sql","");args.putArray("changes").addObject().put("path","/spec/taskManager/resource/memory").put("value","2048m");yield call("repair_propose",args);}
        default->{CompletableFuture.runAsync(()->invocation.getArgument(4,Consumer.class).accept("已生成内存候选，请审阅。")).get(3,TimeUnit.SECONDS);yield answer();}
      };
    }).when(model).stream(any(),anyString(),any(),any(),any());
    var s=session();String id=s.path("id").asText();var request=input(s);var started=harness.start(id,request);
    assertThat(started.path("plan")).isEmpty();assertThat(started.has("context")).isFalse();
    var turn=completed(id,started.path("id").asText());assertThat(turn.path("state").asText()).isEqualTo("AWAITING_APPROVAL");assertThat(turn.path("candidate").isObject()).isTrue();
    assertThat(jobs.get(job.id()).revision()).isEqualTo(job.revision());assertThat(harness.start(id,request).path("id")).isEqualTo(started.path("id"));
    var apply=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("confirmed",true).put("mode","save");
    var applied=harness.apply(id,turn.path("id").asText(),apply,()->"admin");assertThat(applied.path("state").asText()).isEqualTo("APPLIED");
    long saved=jobs.get(job.id()).revision();assertThat(saved).isEqualTo(job.revision()+1);
    harness.apply(id,turn.path("id").asText(),apply,()->"admin");assertThat(jobs.get(job.id()).revision()).isEqualTo(saved);
    verify(kubernetes,never()).call(eq("PUT"),anyString(),any());
  }
  /** 需求整理可选，用户直接描述即可生成候选，应用仍需明确批准。 */
  @Test
  void sqlRequirementsAreOptionalBeforeCandidate()throws Exception{
    String sqlId="sql-"+UUID.randomUUID();var draft=sql.save(sqlId,new SqlDraftRequest(sqlId,"","SELECT 1",mapper.createObjectNode(),0));
    var s=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql").put("sqlId",sqlId));String id=s.path("id").asText();
    var count=new AtomicInteger();doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;
      if(count.getAndIncrement()%2==0){var args=mapper.createObjectNode().put("summary","返回常量2");args.putArray("unknowns");return call("requirements_propose",args);}inv.getArgument(4,Consumer.class).accept("请核对需求。");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var first=harness.start(id,input(s));var requirements=completed(id,first.path("id").asText());assertThat(requirements.has("requirements")).isTrue();
    var raw=records.require("ai_harness_turns",first.path("id").asText());assertThat(tools.schemas(records.require("ai_harness_sessions",id),raw).toString()).contains("sql_propose");
    count.set(0);doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;if(count.getAndIncrement()==0)return call("sql_propose",mapper.createObjectNode().put("sql","SELECT 2"));inv.getArgument(4,Consumer.class).accept("候选已检查，未保存。");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var request=input(harness.get(id)).put("requirementsTurnId",first.path("id").asText()).put("confirmed",true);var second=harness.start(id,request);var candidate=completed(id,second.path("id").asText());
    assertThat(candidate.path("state").asText()).isEqualTo("AWAITING_APPROVAL");assertThat(sql.get(sqlId).path("sql").asText()).isEqualTo("SELECT 1");
    harness.apply(id,second.path("id").asText(),mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("confirmed",true).put("mode","save"),()->"admin");
    assertThat(sql.get(sqlId).path("sql").asText()).isEqualTo("SELECT 2");assertThat(sql.get(sqlId).hasNonNull("jobId")).isFalse();
    sql.delete(sqlId,sql.get(sqlId).path("revision").asLong());assertThat(records.find("ai_harness_sessions",id)).isNull();
  }
  /** 活动删除同时清理衍生评测，迟到模型返回不能重新创建记录。 */
  @Test
  void deleteCancelsLateResponseAndCascadesEvaluation()throws Exception{
    var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
    doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;entered.countDown();try{release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}inv.getArgument(4,Consumer.class).accept("迟到回复");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var s=session();String id=s.path("id").asText();var evaluation=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","evaluation").put("sourceSessionId",id));
    var turn=harness.start(id,input(s));assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
    harness.delete(id,harness.get(id).path("revision").asLong());release.countDown();
    assertThat(records.find("ai_harness_sessions",id)).isNull();assertThat(records.find("ai_harness_sessions",evaluation.path("id").asText())).isNull();
    assertThat(records.find("ai_harness_turns",turn.path("id").asText())).isNull();assertThat(records.listForSubject("ai_harness_plans",id)).isEmpty();assertThat(jobs.get(job.id()).id()).isEqualTo(job.id());
  }
  /** 私有记录不因管理员或共享作业而开放；删除接口仍验证 CSRF。 */
  @Test
  void ownershipCsrfAndViewerBoundaries()throws Exception{
    var s=session();String path="/api/ai/harness/sessions/"+s.path("id").asText();
    mvc.perform(get(path).with(user("other").roles("ADMIN"))).andExpect(status().isForbidden());
    mvc.perform(get("/api/ai/harness/sessions").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
    mvc.perform(delete(path).param("revision",s.path("revision").asText())).andExpect(status().isForbidden());
    mvc.perform(delete(path).param("revision",s.path("revision").asText()).with(csrf())).andExpect(status().isOk());
  }
  /** 旧助手重置轮次保留独立会话，实际领域删除才级联清理。 */
  @Test
  void onlyActualDomainDeletionPurgesSessions(){var s=session();String id=s.path("id").asText();events.publishEvent(new AiJobState.Deleted(job.id()));assertThat(harness.get(id)).isNotNull();synchronized(state.monitor()){state.delete(job.id());}assertThat(records.find("ai_harness_sessions",id)).isNull();}
  /** UNKNOWN 写提交保留恢复入口，不能用删除掩盖未结算动作。 */
  @Test
  void pendingApplicationBlocksDeleteAndNextTurn(){var s=session();String id=s.path("id").asText(),appId=UUID.randomUUID().toString();records.save("ai_harness_applications",appId,mapper.createObjectNode().put("owner","admin").put("sessionId",id).put("jobId",job.id()).put("state","UNKNOWN"),0);
    try{assertThatThrownBy(()->harness.delete(id,s.path("revision").asLong())).hasMessageContaining("核对");assertThatThrownBy(()->harness.start(id,input(s))).hasMessageContaining("核对");}
    finally{var app=records.require("ai_harness_applications",appId);records.delete("ai_harness_applications",appId,app.path("revision").asLong());}
  }
  /** 内容在模型响应期间变更时，最终回复不能声称当前分析成功。 */
  @Test
  void finalResponseRechecksContext()throws Exception{
    doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;jobs.update(job.id(),new DraftRequest(job.name(),"changed",job.flinkVersion(),job.yaml().replace("1024m","3072m"),job.revision()));inv.getArgument(4,Consumer.class).accept("完成");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var s=session();var turn=harness.start(s.path("id").asText(),input(s));assertThat(completed(s.path("id").asText(),turn.path("id").asText()).path("state").asText()).isEqualTo("FAILED");
  }
  /** RUNNING 必须同时满足本次操作、草稿修订和协调代际证明。 */
  @Test
  void runningRequiresOperationRevisionAndReconciliation(){String operation=UUID.randomUUID().toString();live.withObject("/metadata/annotations").put("flink-web/last-request",operation);live.withObject("/status/jobStatus").put("state","RUNNING");
    assertThat(tools.observe(job.id(),operation,job.revision()).path("runningRevisionConfirmed").asBoolean()).isFalse();live.withObject("/status").put("observedGeneration",2);
    assertThat(tools.observe(job.id(),operation,job.revision()).path("runningRevisionConfirmed").asBoolean()).isTrue();assertThat(tools.observe(job.id(),"other",job.revision()).path("runningRevisionConfirmed").asBoolean()).isFalse();
    var changed=live.deepCopy();changed.withObject("/metadata/annotations").put("flink-web/last-request","new-operation");when(kubernetes.call(eq("GET"),eq("/deployments/"+job.deploymentName()),isNull())).thenReturn(live.deepCopy(),changed);
    var raced=tools.observe(job.id(),operation,job.revision());assertThat(raced.path("runningRevisionConfirmed").asBoolean()).isFalse();assertThat(raced.path("issues").toString()).contains("采集期间");
  }
  /** 保存完成但重启未提交时，手动核对仍保留原申请恢复状态。 */
  @Test
  void savedRestartRetainsRecoveryInsteadOfReportingApplied()throws Exception{
    var s=session();String id=s.path("id").asText();var started=harness.start(id,input(s));completed(id,started.path("id").asText());
    String appId=UUID.randomUUID().toString();var request=mapper.createObjectNode().put("requestId",appId).put("confirmed",true).put("mode","restart").put("restoreMode","stateless").put("acceptImpact",true);
    records.save("ai_harness_applications",appId,mapper.createObjectNode().put("owner","admin").put("sessionId",id).put("turnId",started.path("id").asText()).put("jobId",job.id()).put("mode","restart").put("state","SAVED").set("request",request),0);
    var turn=records.require("ai_harness_turns",started.path("id").asText());turn.put("state","SUBMISSION_UNKNOWN").put("applicationId",appId);records.save("ai_harness_turns",turn.path("id").asText(),turn,turn.path("revision").asLong());
    try{var observed=harness.observe(id,turn.path("id").asText());assertThat(observed.path("state").asText()).isEqualTo("SUBMISSION_UNKNOWN");assertThat(observed.at("/application/request/requestId").asText()).isEqualTo(appId);}
    finally{var app=records.require("ai_harness_applications",appId);records.save("ai_harness_applications",appId,app.put("state","FAILED"),app.path("revision").asLong());}
  }
  /** 保留十轮时清理被裁剪轮次关联产物，不留下主体索引脏数据。 */
  @Test
  void retentionPrunesOldTurnsAndPlans()throws Exception{
    var s=session();String id=s.path("id").asText(),first="";
    for(int i=0;i<11;i++){var turn=harness.start(id,input(harness.get(id)));completed(id,turn.path("id").asText());if(i==0){first=turn.path("id").asText();records.save("ai_harness_plans",UUID.randomUUID().toString(),mapper.createObjectNode().put("sessionId",id).put("turnId",first).put("owner","admin"),0);}}
    assertThat(harness.get(id).path("turns")).hasSize(10);assertThat(records.find("ai_harness_turns",first)).isNull();assertThat(records.listForSubject("ai_harness_plans",id)).isEmpty();
  }

  /** 空白开发会话不创建草稿，批准后原子绑定，重复批准不产生第二份草稿。 */
  @Test
  void unboundSqlCreatesAndBindsOnlyAfterApproval()throws Exception{
    var create=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql");
    int before=sql.list().size();var s=harness.create(create);String id=s.path("id").asText();
    assertThat(s.path("sqlId").asText()).isEmpty();assertThat(sql.list()).hasSize(before);
    var count=new AtomicInteger();doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;if(count.getAndIncrement()==0){var args=mapper.createObjectNode().put("summary","返回常量2");args.putArray("unknowns");return call("requirements_propose",args);}inv.getArgument(4,Consumer.class).accept("请确认需求");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var first=harness.start(id,input(s));completed(id,first.path("id").asText());count.set(0);
    doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;if(count.getAndIncrement()==0)return call("sql_propose",mapper.createObjectNode().put("sql","SELECT 2"));inv.getArgument(4,Consumer.class).accept("待审阅");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var next=harness.start(id,input(harness.get(id)).put("requirementsTurnId",first.path("id").asText()).put("confirmed",true));var candidate=completed(id,next.path("id").asText());
    assertThat(candidate.at("/candidate/newDraft").asBoolean()).isTrue();assertThat(sql.list()).hasSize(before);
    var legacy=records.require("ai_harness_plans",candidate.path("planId").asText());legacy.remove(List.of("runtime","execution","runYaml","imageFingerprint"));records.save("ai_harness_plans",legacy.path("id").asText(),legacy,legacy.path("revision").asLong());
    var approval=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("mode","save").put("confirmed",true);
    harness.apply(id,next.path("id").asText(),approval,()->"admin");String sqlId=harness.get(id).path("sqlId").asText();
    assertThat(sql.get(sqlId).path("sql").asText()).isEqualTo("SELECT 2");assertThat(sql.list()).hasSize(before+1);
    harness.apply(id,next.path("id").asText(),approval,()->"admin");assertThat(sql.list()).hasSize(before+1);assertThat(harness.create(create).path("sqlId").asText()).isEqualTo(sqlId);
    harness.delete(id,harness.get(id).path("revision").asLong());assertThat(sql.get(sqlId)).isNotNull();
  }

  /** 外部模型无需逐轮勾选，超过旧模型和工具次数预算仍可继续并保留取消能力。 */
  @Test
  void externalModelContinuesBeyondOldBudgets()throws Exception{
    var config=mapper.createObjectNode().put("name","外部测试").put("model","test").put("baseUrl","https://example.com/v1").put("enabled",true).put("external",true);
    config.put("revision",models.require(modelId,"diagnosis").path("revision").asLong());config.putArray("purposes").add("diagnosis").add("sql");models.save(modelId,config);
    var count=new AtomicInteger();doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;int n=count.getAndIncrement();if(n<28)return call("sql_check",mapper.createObjectNode().put("sql","SELECT "+n+" /*"+"测试观察".repeat(1800)+"*/"));if(n==28)return call("sql_propose",mapper.createObjectNode().put("sql","SELECT 99"));inv.getArgument(4,Consumer.class).accept("完成");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var s=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql"));var t=harness.start(s.path("id").asText(),input(s));var result=completed(s.path("id").asText(),t.path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("AWAITING_APPROVAL");assertThat(result.path("modelRounds").asInt()).isEqualTo(31);assertThat(result.path("toolCalls").asInt()).isEqualTo(30);
    assertThat(result.path("events").size()).isEqualTo(100);assertThat(result.path("eventsTruncated").asBoolean()).isTrue();assertThat(result.path("events").toString()).contains("context.compacted");assertThat(result.path("contextManifest").has("maxModelRounds")).isFalse();
  }

  /** 首轮直接生成候选，批准后一次创建发布并运行；未知结果按原号继续而不重复保存。 */
  @Test
  void directSqlCreationRunAndOriginalRetry()throws Exception{
    when(storage.defaults()).thenReturn(mapper.createObjectNode().put("rootPrefix","artifacts/"));
    var image=mapper.createObjectNode().put("repository","flink").put("version","1.20.5").put("flinkVersion","1.20.5");records.save("images","000-harness-run",image,0);
    when(storage.describe(anyString())).thenAnswer(inv->mapper.createObjectNode().put("filename","flink-web-sql-runner.jar").put("uri","s3://test/runner.jar"));
    if(records.find("settings","downloader")==null)records.save("settings","downloader",mapper.createObjectNode().put("image","example/downloader:test"),0);
    var s=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql"));String id=s.path("id").asText();
    String script="CREATE TABLE sink_table (v INT) WITH ('connector' = 'print'); INSERT INTO sink_table SELECT 1";
    var counter=new AtomicInteger();doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;if(counter.getAndIncrement()==0)return call("sql_propose",mapper.createObjectNode().put("sql",script));inv.getArgument(4,Consumer.class).accept("请审阅并创建运行");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var started=harness.start(id,input(s));var turn=completed(id,started.path("id").asText());
    assertThat(turn.path("state").asText()).as(turn.toString()).isEqualTo("AWAITING_APPROVAL");assertThat(turn.at("/candidate/execution/available").asBoolean()).as(turn.toString()).isTrue();
    var request=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("mode","run").put("confirmed",true).put("acceptImpact",true);
    when(kubernetes.call(anyString(),anyString(),any())).thenAnswer(inv->inv.getArgument(0).equals("GET")?com.fasterxml.jackson.databind.node.MissingNode.getInstance():inv.getArgument(2));
    var result=harness.apply(id,turn.path("id").asText(),request,()->"admin");
    assertThat(result.path("state").asText()).as(result.toString()).isEqualTo("WAITING_OBSERVATION");String sqlId=harness.get(id).path("sqlId").asText();String jobId=sql.get(sqlId).path("jobId").asText();assertThat(jobId).isNotBlank();assertThat(yaml.parse(jobs.get(jobId).yaml()).at("/spec/serviceAccount").asText()).isEqualTo("flink-jobs-flink");
    long revision=sql.get(sqlId).path("revision").asLong();int count=sql.list().size();harness.apply(id,turn.path("id").asText(),request,()->"admin");assertThat(sql.list()).hasSize(count);assertThat(sql.get(sqlId).path("revision").asLong()).isEqualTo(revision);
    var app=records.require("ai_harness_applications",request.path("requestId").asText());records.save("ai_harness_applications",app.path("id").asText(),app.put("state","UNKNOWN"),app.path("revision").asLong());
    harness.apply(id,turn.path("id").asText(),request,()->"admin");assertThat(sql.get(sqlId).path("revision").asLong()).isEqualTo(revision);assertThat(jobs.get(jobId).id()).isEqualTo(jobId);
  }

  /** 模型只写 SQL 文本时自动要求登记候选，保留原始需求而不让用户重复确认。 */
  @Test
  void sqlTextOnlyIsCorrectedToCandidate()throws Exception{
    var s=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql"));String id=s.path("id").asText();
    var counter=new AtomicInteger();doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;var messages=inv.getArgument(2,ArrayNode.class);assertThat(messages.toString()).contains("随机数据写入print");return switch(counter.getAndIncrement()){
      case 0->{inv.getArgument(4,Consumer.class).accept("```sql\nSELECT 1\n```");yield answer();}
      case 1->call("sql_propose",mapper.createObjectNode().put("sql","SELECT 1"));
      default->{inv.getArgument(4,Consumer.class).accept("候选已登记，待审阅");yield answer();}
    };}).when(model).stream(any(),anyString(),any(),any(),any());
    var t=harness.start(id,input(s).put("content","随机数据写入print"));var result=completed(id,t.path("id").asText());assertThat(result.path("state").asText()).isEqualTo("AWAITING_APPROVAL");assertThat(result.has("candidate")).isTrue();assertThat(counter.get()).isEqualTo(3);
    assertThat(sql.list().stream().noneMatch(d->d.path("id").asText().equals(result.at("/candidate/sqlName").asText()))).isTrue();
  }

  /** 反复提交相同不允许脚本时，先提供恢复建议，第三次同类失败就停止，且不创建草稿。 */
  @Test
  void repeatedFailureStopsInsteadOfCallingModelForever()throws Exception{
    var count=new AtomicInteger();doAnswer(inv->{var planning=planning(inv);if(planning!=null)return planning;count.incrementAndGet();return call("sql_propose",mapper.createObjectNode().put("sql","DROP TABLE important"));}).when(model).stream(any(),anyString(),any(),any(),any());
    var s=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql"));int before=sql.list().size();var started=harness.start(s.path("id").asText(),input(s));var result=completed(s.path("id").asText(),started.path("id").asText());
    assertThat(result.path("state").asText()).as(result.toString()).isEqualTo("STALLED");assertThat(count.get()).isEqualTo(3);assertThat(result.at("/recovery/action").asText()).isEqualTo("stop");assertThat(result.path("events").toString()).contains("recovery.recover","recovery.stop");assertThat(sql.list()).hasSize(before);
  }

  /** 已发布SQL使用真实服务关联作业，后续会话既可开发也能查询和修复。 */
  private ObjectNode publishedSqlSession(){
    when(storage.defaults()).thenReturn(mapper.createObjectNode().put("rootPrefix","artifacts/"));
    when(storage.describe(anyString())).thenAnswer(inv->mapper.createObjectNode().put("filename","flink-web-sql-runner.jar").put("uri","s3://test/runner.jar"));
    String imageId="linked-image-"+UUID.randomUUID();records.save("images",imageId,mapper.createObjectNode().put("repository","flink").put("version","1.20.5").put("flinkVersion","1.20.5"),0);
    if(records.find("settings","downloader")==null)records.save("settings","downloader",mapper.createObjectNode().put("image","example/downloader:test"),0);
    String sqlId="linked-sql-"+UUID.randomUUID();var runtime=mapper.createObjectNode().put("imageId",imageId).put("upgradeMode","stateless").put("serviceAccount","existing-account");
    var draft=sql.save(sqlId,new SqlDraftRequest(sqlId,"","CREATE TABLE p (v INT) WITH ('connector'='print'); INSERT INTO p SELECT 1",runtime,0));job=sql.publish(sqlId,draft.path("revision").asLong());
    live=yaml.parse(job.yaml());live.withObject("/metadata").put("name",job.deploymentName()).put("namespace","flink-jobs").put("uid","linked-uid").put("resourceVersion","10").put("generation",1);
    live.withObject("/metadata/labels").put("flink-web/job-id",job.id());live.withObject("/status").put("observedGeneration",1);live.withObject("/status/jobStatus").put("state","RUNNING");
    when(diagnostics.inspect(job.id())).thenReturn(mapper.createObjectNode());when(diagnostics.redactEvidence(eq(job.id()),any())).thenAnswer(inv->inv.getArgument(1));
    return harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql").put("sqlId",sqlId));
  }

  /** 在SQL会话询问状态时实时读取RUNNING并切换本轮意图，不提出部署或写入。 */
  @Test
  void sqlConversationCanQueryCurrentRunningJob()throws Exception{
    var s=publishedSqlSession();var count=new AtomicInteger();doAnswer(inv->{var plan=planning(inv);if(plan!=null){((ObjectNode)plan.at("/tool_calls/0/function")).put("arguments","{\"steps\":[\"查询当前作业状态并核对最新运行证据\"],\"intent\":\"status\"}");return plan;}assertThat(inv.getArgument(2,ArrayNode.class).toString()).contains("runtimeStatus","RUNNING");if(count.getAndIncrement()==0)return call("job_status",mapper.createObjectNode());inv.getArgument(4,Consumer.class).accept("当前作业已经运行");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var t=harness.start(s.path("id").asText(),input(s).put("content","作业现在启动成功了吗？"));var result=completed(s.path("id").asText(),t.path("id").asText());
    assertThat(result.path("scene").asText()).isEqualTo("status");assertThat(result.at("/runtimeStatus/jobState").asText()).isEqualTo("RUNNING");assertThat(result.at("/runtimeStatus/currentRunning").asBoolean()).isTrue();assertThat(result.path("plan").get(0).asText()).contains("查询当前作业状态");assertThat(result.has("candidate")).isFalse();
    verify(kubernetes,never()).call(eq("POST"),anyString(),any());verify(kubernetes,never()).call(eq("PUT"),anyString(),any());
  }

  /** 已发布SQL在同一会话采集证据、修改TM内存并批准重启，保留显式账号。 */
  @Test
  void sqlConversationCanDiagnoseAndRepairPublishedJob()throws Exception{
    var s=publishedSqlSession();var count=new AtomicInteger();doAnswer(inv->{var plan=planning(inv);if(plan!=null)return plan;return switch(count.getAndIncrement()){
      case 0->call("evidence_collect",mapper.createObjectNode().put("category","configuration"));
      case 1->{var args=mapper.createObjectNode().put("sql","");args.putArray("changes").addObject().put("path","/spec/taskManager/resource/memory").put("value","1024m");yield call("repair_propose",args);}
      default->{inv.getArgument(4,Consumer.class).accept("已提出内存修复，等待审阅");yield answer();}
    };}).when(model).stream(any(),anyString(),any(),any(),any());
    var t=harness.start(s.path("id").asText(),input(s).put("content","诊断当前作业，把TM内存调小一些并重新运行"));var result=completed(s.path("id").asText(),t.path("id").asText());assertThat(result.path("state").asText()).as(result.toString()).isEqualTo("AWAITING_APPROVAL");assertThat(result.path("scene").asText()).isEqualTo("repair");assertThat(result.at("/candidate/afterYaml").asText()).contains("1024m","existing-account");
    var approved=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("mode","restart").put("confirmed",true).put("restoreMode","stateless").put("acceptImpact",true);
    var applied=harness.apply(s.path("id").asText(),t.path("id").asText(),approved,()->"admin");assertThat(applied.path("state").asText()).as(applied.toString()).isEqualTo("WAITING_OBSERVATION");assertThat(yaml.parse(jobs.get(job.id()).yaml()).at("/spec/taskManager/resource/memory").asText()).isEqualTo("1024m");assertThat(yaml.parse(jobs.get(job.id()).yaml()).at("/spec/serviceAccount").asText()).isEqualTo("existing-account");
  }

  /** 手动状态投影包含Operator、Pod调度和容器失败，数据不可读时不报告正常。 */
  @Test
  void observationIncludesActualProblemsAndUnavailableState(){
    live.withObject("/status").put("error","serviceAccount missing");var detail=mapper.createObjectNode().put("error","serviceAccount missing");
    var pod=detail.putArray("pods").addObject().put("name","tm-one");pod.putArray("conditions").addObject().put("status","False").put("reason","FailedScheduling").put("message","Insufficient memory");pod.putArray("states").addObject().put("name","flink-main-container").put("ready",false).put("reason","CrashLoopBackOff").put("exitCode",137);
    detail.putArray("events").addObject().put("type","Warning").put("reason","FailedCreate").put("message","serviceaccount does not exist");when(diagnostics.inspect(job.id())).thenReturn(detail);
    var result=tools.observe(job.id(),"old-operation",job.revision());assertThat(result.path("issues").toString()).contains("FailedScheduling","Insufficient memory","CrashLoopBackOff","serviceaccount does not exist");assertThat(result.path("runningRevisionConfirmed").asBoolean()).isFalse();
    when(kubernetes.call(anyString(),anyString(),any())).thenThrow(new IllegalStateException("unavailable"));assertThat(tools.observe(job.id(),"old-operation",job.revision()).path("jobState").asText()).isEqualTo("UNKNOWN");
  }

  /** 缺失账号只能显式补平台默认值，已有账号和任意其他账号都不能被修复工具覆盖。 */
  @Test
  void repairCanOnlyFillMissingDefaultAccount(){
    var changes=mapper.createArrayNode();changes.addObject().put("path","/spec/serviceAccount").put("value","flink-jobs-flink");
    assertThatThrownBy(()->repairs.patched(job,changes)).hasMessageContaining("不允许");
    job=jobs.update(job.id(),new DraftRequest(job.name(),job.description(),job.flinkVersion(),job.yaml().replace("  serviceAccount: flink-jobs-flink\n",""),job.revision()));
    assertThat(repairs.patched(job,changes).at("/spec/serviceAccount").asText()).isEqualTo("flink-jobs-flink");((ObjectNode)changes.get(0)).put("value","arbitrary-admin");assertThatThrownBy(()->repairs.patched(job,changes)).hasMessageContaining("不允许");
  }

  /** 弱模型跳过计划而直接请求工具时，不执行该工具，重复违约会明确停止。 */
  @Test
  void planningIsMandatoryBeforeExecution()throws Exception{
    doAnswer(inv->call("context_read",mapper.createObjectNode())).when(model).stream(any(),anyString(),any(),any(),any());
    var s=session();var started=harness.start(s.path("id").asText(),input(s));var result=completed(s.path("id").asText(),started.path("id").asText());
    assertThat(result.path("state").asText()).isEqualTo("STALLED");assertThat(result.path("plan")).isEmpty();assertThat(result.path("events").toString()).doesNotContain("tool.started");assertThat(result.path("modelRounds").asInt()).isEqualTo(3);
  }

  /** 模型返回正常编号计划时兼容展示，步骤仍来自模型，随后才开放执行工具。 */
  @Test
  void acceptsModelWrittenPlanWithoutStaticFallback()throws Exception{
    var count=new AtomicInteger();doAnswer(inv->{if(count.getAndIncrement()==0){String text="1. 核对本轮最新状态并分析具体原因\n2. 给出针对本次问题的验证方式";inv.getArgument(4,Consumer.class).accept(text);return mapper.createObjectNode().put("role","assistant").put("content",text).put("streamed",true);}inv.getArgument(4,Consumer.class).accept("已核对");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var s=session();var started=harness.start(s.path("id").asText(),input(s));var result=completed(s.path("id").asText(),started.path("id").asText());assertThat(result.path("state").asText()).isEqualTo("SUCCEEDED");assertThat(result.path("plan").toString()).contains("本轮最新状态","本次问题的验证方式");assertThat(result.path("plan")).hasSize(2);
  }

  /** 两个工作线程阻塞时取消排队任务立即释放容量，原身份仍正确结算。 */
  @Test
  void cancelQueuedTaskReleasesExecutorSlot()throws Exception{
    var executor=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField((Object)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(harness),"executor");
    var entered=new CountDownLatch(2);var release=new CountDownLatch(1);var fillers=new ArrayList<FutureTask<Void>>();
    Runnable blocker=()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}};
    executor.execute(blocker);executor.execute(blocker);
    try{assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();var s=session();String id=s.path("id").asText();var queued=harness.start(id,input(s));
      for(int n=0;n<5;n++){var filler=new FutureTask<Void>(()->null);fillers.add(filler);executor.execute(filler);}assertThat(executor.getQueue()).hasSize(6);
      harness.cancel(id,queued.path("id").asText());assertThat(executor.getQueue()).hasSize(5);
      var replacement=harness.start(id,input(harness.get(id)));assertThat(replacement.path("state").asText()).isEqualTo("QUEUED");harness.cancel(id,replacement.path("id").asText());
      assertThat(records.require("ai_harness_turns",queued.path("id").asText()).path("state").asText()).isEqualTo("CANCELED");
    }finally{for(var filler:fillers){filler.cancel(true);executor.remove(filler);}release.countDown();}
  }
  /** 等待用户不占模型线程，回答恢复原目标且同号仅创建一次。 */
  @Test
  void interactionPersistsAndResumesIdempotently()throws Exception{
    var once=new AtomicBoolean();doAnswer(inv->{var plan=planning(inv);if(plan!=null)return plan;
      if(!once.getAndSet(true)){var args=mapper.createObjectNode().put("kind","clarification").put("title","请选择时间字段");args.putArray("questions").add("时间字段是什么？");args.putArray("options");return call("interaction_request",args);}
      inv.getArgument(4,Consumer.class).accept("已按补充继续分析");return answer();
    }).when(model).stream(any(),anyString(),any(),any(),any());
    var session=session();String id=session.path("id").asText();var turn=harness.start(id,input(session));var waiting=completed(id,turn.path("id").asText());
    assertThat(waiting.path("state").asText()).isEqualTo("WAITING_USER");assertThat(harness.get(id).at("/turns/0/interaction/state").asText()).isEqualTo("PENDING");
    var response=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("interactionId",waiting.at("/interaction/id").asText()).put("decision","submit");response.putObject("answers").put("q0","event_time");
    var resumed=harness.respond(id,turn.path("id").asText(),response);completed(id,resumed.path("id").asText());assertThat(harness.respond(id,turn.path("id").asText(),response).path("id")).isEqualTo(resumed.path("id"));
    assertThat(harness.get(id).path("turns")).hasSize(2);assertThat(resumed.path("content").asText()).contains("event_time","时间字段是什么");assertThat(records.require("ai_harness_turns",resumed.path("id").asText()).path("continuationGoal").asText()).isEqualTo(turn.path("content").asText());
    var changed=response.deepCopy().put("requestId",UUID.randomUUID().toString());assertThatThrownBy(()->harness.respond(id,turn.path("id").asText(),changed)).hasMessageContaining("已提交");
  }
  /** 日志同意只来自本次显式动作，文字补充和用户偏好不能替代授权。 */
  @Test
  void logInteractionDoesNotTreatTextAsConsent()throws Exception{
    var once=new AtomicBoolean();doAnswer(inv->{var plan=planning(inv);if(plan!=null)return plan;if(!once.getAndSet(true)){var args=mapper.createObjectNode().put("kind","logs").put("title","需要日志");args.putArray("questions");args.putArray("options");return call("interaction_request",args);}inv.getArgument(4,Consumer.class).accept("继续分析");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    var session=session();String id=session.path("id").asText();var turn=harness.start(id,input(session));var waiting=completed(id,turn.path("id").asText());
    var response=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("interactionId",waiting.at("/interaction/id").asText()).put("decision","submit").put("grantLogs",false);response.putObject("answers").put("note","先看Checkpoint");
    var resumed=harness.respond(id,turn.path("id").asText(),response);completed(id,resumed.path("id").asText());assertThat(records.require("ai_harness_turns",resumed.path("id").asText()).path("includeLogs").asBoolean()).isFalse();
  }
  /** 原资源修订变化和新轮次都使等待卡片失效。 */
  @Test
  void staleInteractionCannotContinue()throws Exception{
    doAnswer(inv->{var plan=planning(inv);if(plan!=null)return plan;var args=mapper.createObjectNode().put("kind","choice").put("title","选排查方向");args.putArray("questions");args.putArray("options").add("Checkpoint");return call("interaction_request",args);}).when(model).stream(any(),anyString(),any(),any(),any());
    var session=session();String id=session.path("id").asText();var turn=harness.start(id,input(session));var waiting=completed(id,turn.path("id").asText());
    jobs.update(job.id(),new DraftRequest(job.name(),"changed",job.flinkVersion(),job.yaml(),job.revision()));
    var response=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("interactionId",waiting.at("/interaction/id").asText()).put("decision","submit");response.putObject("answers").put("choice","Checkpoint");
    assertThatThrownBy(()->harness.respond(id,turn.path("id").asText(),response)).hasMessageContaining("变化");
    assertThat(records.require("ai_harness_turns",turn.path("id").asText()).path("state").asText()).isEqualTo("WAITING_USER");harness.cancel(id,turn.path("id").asText());
  }
  /** 日志快照可继续翻页、检索和清理；不允许用参数绕过本轮同意。 */
  @Test
  void preciseLogReadPagesAndSearchesSamePrivateSnapshot(){
    var args=mapper.createObjectNode().put("pod","jm-1").put("container","flink-main-container").put("lines",10000).put("previous",true);
    assertThatThrownBy(()->capabilities.call("logs_read",args,job.id(),false,"")).hasMessageContaining("授权");
    when(diagnostics.log(job.id(),"jm-1","flink-main-container",10000,true)).thenReturn(mapper.createObjectNode().put("text","x".repeat(15000)+"Checkpoint failed").put("previous",true).put("truncated",true));
    var first=capabilities.call("logs_read",args,job.id(),true,"");assertThat(first.path("hasMore").asBoolean()).isTrue();assertThat(first.path("text").asText()).hasSize(12000);
    var next=capabilities.call("logs_page",mapper.createObjectNode().put("logId",first.path("logId").asText()).put("offset",12000),"",true,"");assertThat(next.path("text").asText()).endsWith("Checkpoint failed");assertThat(next.path("truncated").asBoolean()).isTrue();
    var found=capabilities.call("logs_search",mapper.createObjectNode().put("logId",first.path("logId").asText()).put("query","Checkpoint"),"",true,"");assertThat(found.path("matches")).hasSize(1);
    assertThatThrownBy(()->capabilities.call("logs_page",mapper.createObjectNode().put("logId",first.path("logId").asText()),"",false,"")).hasMessageContaining("授权");
    assertThat(capabilities.call("logs_release",mapper.createObjectNode().put("logId",first.path("logId").asText()),"",true,"").path("released").asBoolean()).isTrue();assertThat(records.find("ai_harness_log_snapshots",first.path("logId").asText())).isNull();
  }
  /** Checkpoint下钻端点绑定实际运行身份，读取期间重建会拒绝混合证据。 */
  @Test
  void checkpointDetailsBindCurrentRun(){
    String jid="a".repeat(32),vertex="b".repeat(32);live.withObject("/status/jobStatus").put("jobId",jid);
    when(evidenceClient.read(job.deploymentName(),"jobs/"+jid+"/checkpoints/details/12/subtasks/"+vertex)).thenReturn(mapper.createObjectNode().putArray("subtasks").addObject().put("index",0));
    var args=mapper.createObjectNode().put("checkpointId",12).put("vertexId",vertex);
    var out=capabilities.call("checkpoint_operator_detail",args,job.id(),false,"");assertThat(out.path("flinkJobId").asText()).isEqualTo(jid);assertThat(out.path("source").asText()).contains("subtasks/"+vertex);
    when(evidenceClient.read(job.deploymentName(),"jobs/"+jid+"/checkpoints")).thenAnswer(inv->{live.withObject("/status/jobStatus").put("jobId","c".repeat(32));return mapper.createObjectNode();});
    assertThatThrownBy(()->capabilities.call("checkpoint_overview",mapper.createObjectNode(),job.id(),false,"")).hasMessageContaining("身份变化");
  }
  /** MCP协议、私有偏好和CSRF走真实HTTP过滤链。 */
  @Test
  void mcpDiscoveryPreferencesAndHttpGuards()throws Exception{
    var req=mapper.createObjectNode().put("jsonrpc","2.0").put("id",1).put("method","initialize");req.putObject("params").put("protocolVersion","2025-11-25");
    mvc.perform(post("/api/ai/mcp").with(csrf()).contentType("application/json").content(req.toString())).andExpect(status().isOk()).andExpect(jsonPath("$.result.serverInfo.version").value("0.0.10"));
    mvc.perform(post("/api/ai/mcp").contentType("application/json").content(req.toString())).andExpect(status().isForbidden());
    mvc.perform(post("/api/ai/mcp").with(csrf()).header("Origin","https://evil.example").contentType("application/json").content(req.toString())).andExpect(status().isForbidden());
    org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("admin","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))));
    req.put("method","tools/list");assertThat(mcp.dispatch(req,false).path("result").path("tools").toString()).contains("checkpoint_operator_detail","logs_read","catalog_metadata");
    req.put("method","prompts/list");assertThat(mcp.dispatch(req,false).path("result").path("prompts")).hasSize(9);
    var old=preferences.get();var input=mapper.createObjectNode().put("revision",old.path("revision").asLong());input.putObject("values").put("responseStyle","先给结论");var saved=preferences.save(input);assertThat(preferences.get().at("/values/responseStyle").asText()).isEqualTo("先给结论");
    assertThatThrownBy(()->preferences.save(input)).hasMessageContaining("修改");
    var invalid=mapper.createObjectNode().put("revision",saved.path("revision").asLong());invalid.putObject("values").put("allowRestart","true");assertThatThrownBy(()->preferences.save(invalid)).hasMessageContaining("无效");
    preferences.save(mapper.createObjectNode().put("revision",saved.path("revision").asLong()).set("values",mapper.createObjectNode()));
  }

  /** SQL真实验证只在卡片明确批准后提交，网络不进入元数据事务。 */
  @Test
  void verificationRequiresConcreteUserConfirmation()throws Exception{
    String sqlId="verify-"+UUID.randomUUID();sql.save(sqlId,new SqlDraftRequest(sqlId,"","SELECT 1",mapper.createObjectNode(),0));
    var once=new AtomicBoolean();doAnswer(inv->{var plan=planning(inv);if(plan!=null)return plan;if(!once.getAndSet(true))return call("sql_verification_propose",mapper.createObjectNode());inv.getArgument(4,Consumer.class).accept("验证已受理，需核对状态");return answer();}).when(model).stream(any(),anyString(),any(),any(),any());
    when(executions.submit(eq(sqlId),anyLong(),anyString())).thenAnswer(inv->{assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return mapper.createObjectNode().put("id","validation-one").put("state","RUNNING");});
    var s=harness.create(mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("kind","sql").put("sqlId",sqlId));String id=s.path("id").asText();var turn=harness.start(id,input(s));var waiting=completed(id,turn.path("id").asText());assertThat(waiting.at("/interaction/kind").asText()).isEqualTo("verification");verify(executions,never()).submit(anyString(),anyLong(),anyString());
    var response=mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("interactionId",waiting.at("/interaction/id").asText()).put("decision","submit").put("confirmedVerification",true);response.putObject("answers");
    var next=harness.respond(id,turn.path("id").asText(),response);completed(id,next.path("id").asText());harness.respond(id,turn.path("id").asText(),response);verify(executions,times(1)).submit(eq(sqlId),anyLong(),anyString());
    assertThat(records.require("ai_harness_turns",next.path("id").asText()).at("/interactionResponse/verificationResult/id").asText()).isEqualTo("validation-one");
  }
  /** 发现过滤不能替代调用鉴权，跨用户偏好与日志快照保持隔离。 */
  @Test
  void capabilitiesRecheckRoleAndPrivateLogOwner(){
    var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
    try{
      org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("operator","",List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_OPERATOR"))));
      assertThat(capabilities.tools().toString()).doesNotContain("catalog_metadata");assertThatThrownBy(()->capabilities.call("catalog_metadata",mapper.createObjectNode().put("catalogId","c"),"",false,"")).hasMessageContaining("角色");
      var preference=preferences.get();assertThat(preference.path("values").isEmpty()).isTrue();
      var log=records.save("ai_harness_log_snapshots","private-log-"+UUID.randomUUID(),mapper.createObjectNode().put("owner","someone-else").put("expiresAt",java.time.Instant.now().plusSeconds(600).toString()).put("text","private"),0);
      assertThatThrownBy(()->capabilities.call("logs_page",mapper.createObjectNode().put("logId",log.path("id").asText()),"",true,"")).hasMessageContaining("其他用户");
    }finally{org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);}
  }

  /** MCP解析错误、通知和带端口的同源请求遵守JSON-RPC与HTTP约定。 */
  @Test
  void mcpParseErrorsNotificationsAndOriginPort()throws Exception{
    mvc.perform(post("/api/ai/mcp").with(csrf()).contentType("application/json").content("{" )).andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32700));
    mvc.perform(post("/api/ai/mcp").with(csrf()).contentType("application/json").content("[]")).andExpect(status().isOk()).andExpect(jsonPath("$.error.code").value(-32600));
    mvc.perform(post("/api/ai/mcp").with(csrf()).contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).andExpect(status().isAccepted());
    mvc.perform(post("/api/ai/mcp").with(csrf()).header("Host","localhost:30080").header("Origin","http://localhost:30080").contentType("application/json").content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}")).andExpect(status().isOk());
    mvc.perform(post("/api/ai/mcp").with(csrf()).header("MCP-Protocol-Version","unsupported").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
  }
  /** 大Checkpoint详情按算子及子任务分页，偏移和64位ID不会截断。 */
  @Test
  void checkpointPagesSupportLargeIds(){
    String jid="a".repeat(32);live.withObject("/status/jobStatus").put("jobId",jid);var data=mapper.createObjectNode();var rows=data.putArray("subtasks");for(int n=0;n<120;n++)rows.addObject().put("index",n);
    when(evidenceClient.read(job.deploymentName(),"jobs/"+jid+"/checkpoints/details/9999999999/subtasks/"+"b".repeat(32))).thenReturn(data);
    var args=mapper.createObjectNode().put("checkpointId",9999999999L).put("vertexId","b".repeat(32)).put("offset",50);
    var out=capabilities.call("checkpoint_operator_detail",args,job.id(),false,"");assertThat(out.at("/data/subtasks")).hasSize(50);assertThat(out.at("/data/subtasks/0/index").asInt()).isEqualTo(50);assertThat(out.at("/data/page/nextOffset").asInt()).isEqualTo(100);
  }

}
