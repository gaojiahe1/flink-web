package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.ai.AiModelClient;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.ai.*;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** 使用真实 SQLite、作业保存和运行编排，覆盖持续诊断、有限记忆和显式应用边界。 */
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(username="admin",roles="ADMIN")
class AiJobIntegrationTest {
  static final Path DATABASE;
  static {try {DATABASE=Files.createTempDirectory("flink-ai-job-").resolve("test.db");}catch(Exception e){throw new RuntimeException(e);}}
  @Autowired ObjectMapper mapper;
  @Autowired RecordRepository records;
  @Autowired JobService jobs;
  @Autowired YamlService yaml;
  @Autowired AiJobService conversations;
  @Autowired AiTaskService tasks;
  @Autowired AiModelService models;
  @Autowired com.flinkweb.service.sql.SqlJobService sqlJobs;
  @Autowired MockMvc mvc;
  @MockitoBean AiModelClient model;
  @MockitoBean KubernetesClient kubernetes;
  @MockitoBean DiagnosticService diagnostics;
  @MockitoBean S3StorageClient storage;
  JobDraft job;
  ObjectNode live;
  ObjectNode answer;
  String modelId;
  AtomicReference<String> received=new AtomicReference<>();
  AtomicInteger writes=new AtomicInteger();

  /** 独立测试库和合成主密钥，不读取用户现有数据库或集群凭据。 */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("platform.storage.type",()->"local");registry.add("platform.storage.local-path",()->DATABASE.toString());
    registry.add("platform.storage.initialize",()->true);registry.add("platform.namespace",()->"flink-jobs");
    registry.add("platform.security.password",()->"Test-password-12345");registry.add("platform.security.master-key",()->"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
    registry.add("platform.artifacts.downloader-image",()->"registry.test/downloader:0.0.7");
  }

  /** 每项建立独立草稿和可变的归属正确资源，集群替身按真实 dry-run 保留提交字段。 */
  @BeforeEach
  void setup() throws Exception {
    String name="ai-job-"+UUID.randomUUID().toString().substring(0,8);
    String source="apiVersion: flink.apache.org/v1beta1\nkind: FlinkDeployment\nmetadata:\n  name: "+name+"\nspec:\n  image: flink:1.20.5\n  flinkVersion: v1_20\n  serviceAccount: flink-jobs-flink\n  taskManager:\n    resource:\n      memory: 1024m\n      cpu: 1\n  job:\n    jarURI: local:///opt/flink/examples/streaming/StateMachineExample.jar\n    parallelism: 1\n    upgradeMode: stateless\n";
    job=jobs.create(new DraftRequest(name,"","1.20.5",source,0));
    live=yaml.parse(source);live.withObject("/metadata").put("name",job.deploymentName()).put("namespace","flink-jobs").put("uid","uid-one").put("resourceVersion","10");
    live.withObject("/metadata/labels").put("flink-web/job-id",job.id());
    live.withObject("/status/jobStatus").put("state","FAILED");
    when(kubernetes.base()).thenReturn("/deployments");
    when(kubernetes.call(anyString(),anyString(),any())).thenAnswer(call->{
      String method=call.getArgument(0),path=call.getArgument(1);
      if(method.equals("GET"))return live==null?MissingNode.getInstance():live.deepCopy();
      ObjectNode body=call.getArgument(2);
      if((method.equals("PUT")||method.equals("POST")) && !path.contains("dryRun")) {writes.incrementAndGet();live=body.deepCopy();live.withObject("/metadata").put("uid","uid-one").put("resourceVersion","11");}
      return body==null?mapper.createObjectNode():body.deepCopy();
    });
    var detail=mapper.createObjectNode().put("error","OOMKilled password=hidden-test");
    detail.putArray("pods").addObject().put("name","tm-one").putArray("states").addObject().put("name","flink-main-container").put("restarts",2);
    when(diagnostics.inspect(job.id())).thenReturn(detail);
    when(diagnostics.log(eq(job.id()),anyString(),anyString(),eq(120),eq(true))).thenReturn(mapper.createObjectNode().put("text","container memory pressure"));
    answer=mapper.createObjectNode().put("reply","容器内存不足可能导致退出，需结合证据核对").put("summary","保留业务逻辑；已确认容器被终止，待核对流量峰值");
    answer.putArray("unknowns");answer.putArray("risks");
    when(model.complete(any(),anyString(),anyString(),anyString())).thenAnswer(call->{received.set(call.getArgument(3));return answer.deepCopy();});
    modelId="model-"+UUID.randomUUID();
    var config=mapper.createObjectNode().put("name","诊断替身").put("model","test").put("baseUrl","https://example.com/v1").put("external",false).put("enabled",true);
    config.putArray("purposes").add("diagnosis");models.save(modelId,config);
  }

  /** 追加一轮用户补充并运行原有异步任务入口。 */
  private ObjectNode turn(String message,String mode) throws Exception {
    var current=conversations.get(job.id());
    current=conversations.append(job.id(),mapper.createObjectNode().put("messageId",UUID.randomUUID().toString()).put("revision",current.path("revision").asLong()).put("content",message).put("mode",mode));
    var task=tasks.create(mapper.createObjectNode().put("kind","diagnosis").put("mode",mode).put("modelId",modelId).put("jobId",job.id()).put("requestId",UUID.randomUUID().toString()).put("includeLogs",true).put("jobConversationVersion",current.path("contentVersion").asLong()));
    String id=task.path("id").asText();
    org.awaitility.Awaitility.await().pollInSameThread().atMost(Duration.ofSeconds(10)).until(()->!Set.of("QUEUED","RUNNING").contains(tasks.get(id).path("state").asText()));
    return tasks.get(id);
  }

  /** 候选内存修改仍只保存在审阅方案中。 */
  private ObjectNode plan() throws Exception {
    answer.putArray("changes").addObject().put("path","/spec/taskManager/resource/memory").put("value","2048m");
    assertThat(turn("保持 SQL 逻辑，评估增加内存","repair").path("state").asText()).isEqualTo("SUCCEEDED");
    return (ObjectNode)conversations.get(job.id()).path("plan");
  }

  /** 构造显式确认的原应用请求。 */
  private ObjectNode application(String mode) {return mapper.createObjectNode().put("requestId",UUID.randomUUID().toString()).put("mode",mode).put("confirmed",true).put("restoreMode","stateless").put("acceptImpact",true);}

  /** 建立真实 SQL 保存发布链路，只将下载文件和集群替换为不联网的登记。 */
  private String sqlJob() {
    String name="ai-sql-"+UUID.randomUUID().toString().substring(0,8),image="image-"+UUID.randomUUID(),runner="runner-"+UUID.randomUUID();
    records.save("images",image,mapper.createObjectNode().put("repository","flink").put("version","1.20.5").put("flinkVersion","1.20.5"),0);
    records.save("artifacts",runner,mapper.createObjectNode().put("filename","runner.jar").put("uri","https://files.example/runner.jar"),0);
    String script="CREATE TABLE src (id INT) WITH ('connector'='datagen'); CREATE TABLE dst (id INT) WITH ('connector'='print'); INSERT INTO dst SELECT id FROM src;";
    var runtime=mapper.createObjectNode().put("imageId",image).put("runnerArtifactId",runner).put("parallelism",1).put("upgradeMode","stateless");
    var saved=sqlJobs.save(name,new com.flinkweb.dto.sql.SqlDraftRequest(name,"",script,runtime,0));
    job=sqlJobs.publish(name,saved.path("revision").asLong());
    live=yaml.parse(job.yaml());live.withObject("/metadata").put("name",job.deploymentName()).put("uid","uid-one").put("resourceVersion","10");
    live.withObject("/metadata/labels").put("flink-web/job-id",job.id());live.withObject("/status/jobStatus").put("state","FAILED");
    when(diagnostics.inspect(job.id())).thenReturn(mapper.createObjectNode().set("pods",mapper.createArrayNode()));
    return name;
  }

  /** SQL 和配置先只保存，再发布新脚本；新 args 不被旧运行配置覆盖。 */
  @Test
  void sqlRepairUsesDraftThenPublishesNewArgumentsAndConfiguration() throws Exception {
    String id=sqlJob(),before=sqlJobs.get(id).path("sql").asText(),after=before.replace("SELECT id FROM","SELECT id + 1 FROM");
    String deployedBefore=job.yaml();answer.put("sql",after);
    var first=plan();
    assertThat(first.path("beforeSql").asText()).isEqualTo(before);
    assertThat(first.path("sql").asText()).isEqualTo(after);
    assertThat(conversations.apply(job.id(),first.path("id").asText(),application("save"),()->"admin").path("state").asText()).isEqualTo("SAVED");
    assertThat(sqlJobs.get(id).path("sql").asText()).isEqualTo(after);assertThat(jobs.get(job.id()).yaml()).isEqualTo(deployedBefore);assertThat(writes.get()).isZero();
    after=before.replace("SELECT id FROM","SELECT id + 2 FROM");answer.put("sql",after);
    var second=plan();
    assertThat(conversations.apply(job.id(),second.path("id").asText(),application("restart"),()->"admin").path("state").asText()).isEqualTo("ACCEPTED");
    StringBuilder arguments=new StringBuilder();
    for(var arg:live.at("/spec/job/args"))if(!arg.asText().equals("--statement")) arguments.append(new String(Base64.getDecoder().decode(arg.asText()),java.nio.charset.StandardCharsets.UTF_8));
    assertThat(arguments.toString()).contains("SELECT id + 2 FROM").doesNotContain("SELECT id FROM");
    assertThat(live.at("/spec/taskManager/resource/memory").asText()).isEqualTo("2048m");
  }

  /** 每轮包含总结、近期消息和受限证据，配置投影不发送 env。 */
  @Test
  void contextUsesMemoryAndOwnedLogsWithoutEnvironment() throws Exception {
    live.withObject("/spec/podTemplate/spec").putArray("containers").addObject().put("name","flink-main-container").putArray("env").addObject().put("name","BUSINESS_PRIVATE").put("value","do-not-send-this");
    assertThat(turn("必须保留业务逻辑","chat").path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(turn("请继续分析","chat").path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(received.get()).contains("必须保留业务逻辑","保留业务逻辑","1024m","container memory pressure","[REDACTED]").doesNotContain("do-not-send-this","hidden-test");
    verify(diagnostics,atLeastOnce()).log(eq(job.id()),eq("tm-one"),eq("flink-main-container"),eq(120),eq(true));
    assertThat(writes.get()).isZero();
  }

  /** 两份日志优先覆盖不同 Pod 的 Flink 主容器，初始化下载器不能挤掉 TaskManager。 */
  @Test
  void logBudgetPrioritizesFlinkContainersAcrossPods() throws Exception {
    var detail=mapper.createObjectNode();var pods=detail.putArray("pods");
    var jm=pods.addObject().put("name","jm-one").putArray("states");
    jm.addObject().put("name","fw-download");jm.addObject().put("name","flink-main-container");
    var tm=pods.addObject().put("name","tm-one").putArray("states");
    tm.addObject().put("name","fw-download");tm.addObject().put("name","flink-main-container");
    when(diagnostics.inspect(job.id())).thenReturn(detail);
    when(diagnostics.log(eq(job.id()),anyString(),anyString(),eq(120),eq(false)))
        .thenAnswer(call->mapper.createObjectNode().put("text",call.getArgument(1,String.class)+" application output"));
    assertThat(turn("核对JM状态及TM输出","chat").path("state").asText()).isEqualTo("SUCCEEDED");
    assertThat(received.get()).contains("jm-one application output","tm-one application output");
    verify(diagnostics,never()).log(anyString(),anyString(),eq("fw-download"),anyInt(),anyBoolean());
    assertThat(conversations.get(job.id()).at("/evidence/logs")).hasSize(2);
  }

  /** 成功总结之后裁剪旧消息，任务记录同时有限保留。 */
  @Test
  void successfulSummaryCompactsOriginalsAndBoundsTasks() throws Exception {
    for(int i=0;i<13;i++) assertThat(turn("补充第"+i+"轮，保持业务约束","chat").path("state").asText()).isEqualTo("SUCCEEDED");
    var value=conversations.get(job.id());
    assertThat(value.path("messages").size()).isEqualTo(16);assertThat(value.path("compressedMessages").asInt()).isEqualTo(10);
    assertThat(value.path("summary").asText()).contains("保留业务逻辑");
    assertThat(records.listForSubject("ai_tasks",job.id())).hasSize(5);
  }

  /** 模型未给出有效总结时，不删除用户原文或伪造成功消息。 */
  @Test
  void failedSummaryPreservesOriginalMessages() throws Exception {
    answer.put("summary","");
    assertThat(turn("不能丢失的原始约束","chat").path("state").asText()).isEqualTo("FAILED");
    assertThat(conversations.get(job.id()).path("messages")).hasSize(1);
    assertThat(conversations.get(job.id()).path("compressedMessages").asInt()).isZero();
  }

  /** 仅保存不会访问集群写入口，同号重试不会重复更新草稿。 */
  @Test
  void saveIsExplicitAndIdempotentWithoutRuntimeWrites() throws Exception {
    var plan=plan();assertThat(jobs.get(job.id()).yaml()).contains("1024m");
    var input=application("save");
    var saved=conversations.apply(job.id(),plan.path("id").asText(),input,()->"admin");
    assertThat(saved.path("state").asText()).isEqualTo("SAVED");
    long revision=jobs.get(job.id()).revision();
    assertThat(jobs.get(job.id()).yaml()).contains("2048m");
    conversations.apply(job.id(),plan.path("id").asText(),input,()->"admin");
    assertThat(jobs.get(job.id()).revision()).isEqualTo(revision);assertThat(writes.get()).isZero();
    assertThatThrownBy(()->conversations.apply(job.id(),plan.path("id").asText(),application("save"),()->"admin")).hasMessageContaining("已提交");
  }

  /** 应用受理仍等待观测，同号请求只提交一次新配置。 */
  @Test
  void restartDeploysNewConfigurationOnceAndReportsAccepted() throws Exception {
    var plan=plan();var input=application("restart");
    var applied=conversations.apply(job.id(),plan.path("id").asText(),input,()->"admin");
    assertThat(applied.path("state").asText()).isEqualTo("ACCEPTED");
    assertThat(live.at("/spec/taskManager/resource/memory").asText()).isEqualTo("2048m");
    assertThat(writes.get()).isEqualTo(1);
    conversations.apply(job.id(),plan.path("id").asText(),input,()->"admin");assertThat(writes.get()).isEqualTo(1);
    assertThat(applied.path("message").asText()).contains("手动刷新");
  }

  /** 未确认应用阻止新方案取代恢复入口，同号完成后才允许继续对话。 */
  @Test
  void uncertainApplicationPreservesOriginalRecoveryEntry() throws Exception {
    var plan=plan();var input=application("restart");
    var saved=conversations.apply(job.id(),plan.path("id").asText(),input,()->"admin");
    var application=records.require("ai_job_applications",saved.path("id").asText());
    application.remove("operationId");application.put("state","UNKNOWN");
    records.save("ai_job_applications",application.path("id").asText(),application,application.path("revision").asLong());
    var current=conversations.get(job.id());
    assertThatThrownBy(()->conversations.append(job.id(),mapper.createObjectNode().put("messageId",UUID.randomUUID().toString()).put("revision",current.path("revision").asLong()).put("content","生成另一个方案"))).hasMessageContaining("原应用尚未确认");
    assertThatThrownBy(()->conversations.context(job.id(),current.path("contentVersion").asLong(),false)).hasMessageContaining("原应用尚未确认");
    assertThat(conversations.get(job.id()).at("/plan/id").asText()).isEqualTo(plan.path("id").asText());
    assertThat(conversations.apply(job.id(),plan.path("id").asText(),input,()->"admin").path("state").asText()).isEqualTo("ACCEPTED");
    assertThat(conversations.append(job.id(),mapper.createObjectNode().put("messageId",UUID.randomUUID().toString()).put("revision",current.path("revision").asLong()).put("content","继续观察")).path("messages")).hasSize(3);
  }

  /** 修改总结后旧方案不可应用。 */
  @Test
  void additionalConversationInvalidatesPlan() throws Exception {
    var plan=plan();var current=conversations.get(job.id());
    conversations.summary(job.id(),mapper.createObjectNode().put("revision",current.path("revision").asLong()).put("summary","新的约束：暂不增加内存"));
    assertThatThrownBy(()->conversations.apply(job.id(),plan.path("id").asText(),application("save"),()->"admin")).hasMessageContaining("总结已更新");
    assertThat(jobs.get(job.id()).yaml()).contains("1024m");
  }

  /** 资源 UID 替换后禁止根据旧证据写配置或部署。 */
  @Test
  void replacedResourceRejectsRestartBeforeSaving() throws Exception {
    var plan=plan();live.withObject("/metadata").put("uid","replacement");
    assertThatThrownBy(()->conversations.apply(job.id(),plan.path("id").asText(),application("restart"),()->"admin")).hasMessageContaining("身份或配置已经变化");
    assertThat(jobs.get(job.id()).yaml()).contains("1024m");assertThat(writes.get()).isZero();
  }

  /** 无恢复影响确认或失败作业没有保存点时，不允许默认降级重启。 */
  @Test
  void restartRequiresExplicitRecoveryAndImpactAcceptance() throws Exception {
    var plan=plan();
    assertThatThrownBy(()->conversations.apply(job.id(),plan.path("id").asText(),application("restart").put("acceptImpact",false),()->"admin")).hasMessageContaining("确认");
    assertThatThrownBy(()->conversations.apply(job.id(),plan.path("id").asText(),application("restart").put("restoreMode","savepoint"),()->"admin")).hasMessageContaining("保存点路径");
    assertThat(writes.get()).isZero();
  }

  /** 模型不能修改 namespace、程序入口或把整份 spec 当补丁。 */
  @Test
  void untrustedPatchCannotModifyIdentity() throws Exception {
    answer.putArray("changes").addObject().put("path","/metadata/namespace").put("value","another-team");
    assertThat(turn("给我修复配置","repair").path("state").asText()).isEqualTo("FAILED");
    assertThat(conversations.get(job.id()).has("plan")).isFalse();assertThat(writes.get()).isZero();
  }

  /** 私有会话和开发者应用边界均在直接 HTTP 请求上成立。 */
  @Test
  void privateMemoryAndRoleBoundary() throws Exception {
    var plan=plan();
    mvc.perform(get("/api/ai/jobs/"+job.id()+"/conversation").with(user("another").roles("ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.messages.length()").value(0));
    mvc.perform(post("/api/ai/jobs/"+job.id()+"/plans/"+plan.path("id").asText()+"/apply").with(user("operator").roles("OPERATOR")).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(application("save").toString())).andExpect(status().isForbidden());
    mvc.perform(get("/api/ai/jobs/"+job.id()+"/conversation").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
  }

  /** 删除作业清理所有用户 AI 数据，迟到模型响应不能重新写回。 */
  @Test
  void deletionCancelsAndRemovesEveryOwnerWithoutLateResurrection() throws Exception {
    plan();
    records.save("ai_job_conversations",UUID.randomUUID().toString(),mapper.createObjectNode().put("jobId",job.id()).put("owner","other"),0);
    var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var exited=new CountDownLatch(1);
    when(model.complete(any(),anyString(),anyString(),anyString())).thenAnswer(call->{entered.countDown();try {while(true)try {resume.await();break;}catch(InterruptedException ignored){}return answer.deepCopy();}finally {exited.countDown();}});
    var current=conversations.get(job.id());current=conversations.append(job.id(),mapper.createObjectNode().put("messageId",UUID.randomUUID().toString()).put("revision",current.path("revision").asLong()).put("content","继续排查"));
    tasks.create(mapper.createObjectNode().put("kind","diagnosis").put("modelId",modelId).put("jobId",job.id()).put("requestId",UUID.randomUUID().toString()).put("jobConversationVersion",current.path("contentVersion").asLong()));
    assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
    try {jobs.delete(job.id(),jobs.get(job.id()).revision());} finally {resume.countDown();}
    assertThat(exited.await(5,TimeUnit.SECONDS)).isTrue();
    var executor=(ThreadPoolExecutor)org.springframework.test.util.ReflectionTestUtils.getField(tasks,"executor");
    org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).until(()->executor.getActiveCount()==0);
    for(String collection:List.of("ai_tasks","ai_job_conversations","ai_job_evidence","ai_job_plans","ai_job_applications"))
      assertThat(records.list(collection).stream().filter(r->job.id().equals(r.path("jobId").asText()))).isEmpty();
    assertThatThrownBy(()->conversations.get(job.id())).hasMessageContaining("不存在");
  }
}
