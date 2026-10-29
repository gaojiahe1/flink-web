package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.storage.S3StorageClient;
import com.flinkweb.dto.job.creation.DraftRequest;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.runtime.OperationService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.SqlValidationService;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** 结构化修复补丁的确定性检查、完整对比、保存与运行操作编排。 */
@Component
public class AiJobRepairCoordinator {
  private final ObjectMapper mapper;
  private final RecordRepository records;
  private final JobService jobs;
  private final SqlJobService sqlJobs;
  private final YamlService yaml;
  private final SqlValidationService validation;
  private final OperationService operations;
  private final AccessService access;
  private final AiContentGuard guard;
  private final AiJobEvidenceCollector evidence;
  private final S3StorageClient storage;
  private final TransactionTemplate transaction;

  /** 复用已有保存、Parser、发布和受版本保护的运行入口。 */
  public AiJobRepairCoordinator(ObjectMapper mapper,RecordRepository records,JobService jobs,SqlJobService sqlJobs,
      YamlService yaml,SqlValidationService validation,OperationService operations,AccessService access,
      AiContentGuard guard,AiJobEvidenceCollector evidence,S3StorageClient storage,org.springframework.transaction.PlatformTransactionManager manager) {
    this.mapper=mapper;this.records=records;this.jobs=jobs;this.sqlJobs=sqlJobs;this.yaml=yaml;this.validation=validation;
    this.operations=operations;this.access=access;this.guard=guard;this.evidence=evidence;this.storage=storage;this.transaction=new TransactionTemplate(manager);
  }

  /** 仅允许资源及明确的 Flink 参数标量补丁，不接受整份配置覆盖和身份修改。 */
  public ObjectNode patched(JobDraft job,JsonNode changes) {
    if(!changes.isArray() || changes.size()>16) fail("AI_INVALID_PATCH","配置修改须为最多16项的字段补丁");
    var node=yaml.parse(job.yaml());
    var paths=new java.util.HashSet<String>();
    for(var change:changes) {
      String pointer=change.path("path").asText();
      JsonNode value=change.path("value");
      boolean resource=pointer.matches("/spec/(taskManager|jobManager)/resource/(memory|cpu)");
      boolean parallel=pointer.equals("/spec/job/parallelism");
      boolean config=pointer.startsWith("/spec/flinkConfiguration/")
          && pointer.substring("/spec/flinkConfiguration/".length()).matches("(taskmanager\\.memory\\.[a-z.-]+|jobmanager\\.memory\\.[a-z.-]+|taskmanager\\.numberOfTaskSlots|parallelism\\.default|execution\\.checkpointing\\.(interval|timeout)|restart-strategy\\.[a-z.-]+|table\\.exec\\.[a-z.-]+)");
      if(!change.isObject() || !paths.add(pointer) || !(resource||parallel||config)
          || value.isMissingNode() || value.isNull() || value.isContainerNode()
          || value.toString().length()>512) fail("AI_INVALID_PATCH","建议包含重复、非标量或不允许修改的配置字段");
      if(parallel && (!value.isIntegralNumber() || value.asInt()<1 || value.asInt()>10000)) fail("AI_INVALID_PATCH","并行度必须为1到10000的整数");
      if(pointer.endsWith("/cpu") && (!value.isNumber() || value.asDouble()<=0 || value.asDouble()>1000)) fail("AI_INVALID_PATCH","CPU 必须为正数");
      if(pointer.endsWith("/memory") && (!value.isTextual() || !value.asText().matches("(?i)[1-9][0-9]*(\\.[0-9]+)?[mg]"))) fail("AI_INVALID_PATCH","内存须为带 m/g 单位的正数");
      if(config && (!value.isTextual() || value.asText().isBlank())) fail("AI_INVALID_PATCH","Flink 配置值必须为非空字符串");
      guard.requireSafe(value.toString());
      int separator=pointer.lastIndexOf('/');
      String parent=pointer.substring(0,separator), key=pointer.substring(separator+1).replace("~1","/").replace("~0","~");
      node.withObject(parent).set(key,value.deepCopy());
    }
    operations.validate(yaml.dump(node),node.at("/metadata/name").asText(),false);
    return node;
  }

  /** 完整 SQL 只走已有语法边界，不允许模型添加运行命令或保留脱敏占位符。 */
  private void validateSql(String script) {
    guard.requireSafe(script);
    if(script.contains("[REDACTED]")) fail("AI_INVALID_SQL","建议包含脱敏占位符，请先将凭据改为平台引用");
    var checked=validation.validateVerification(script);
    for(String statement:checked.statements())
      if(!statement.matches("(?is)^(CREATE\\s+(?:TEMPORARY\\s+)?(?:TABLE|VIEW)\\b|INSERT\\s+(?:INTO|OVERWRITE)\\b|SELECT\\b|WITH\\b|VALUES\\b).*"))
        fail("AI_INVALID_SQL","修复仅允许表/视图定义与查询或 INSERT");
  }

  /** 同时修改 SQL 与 YAML 时，以同一个运行配置生成候选，避免旧 args 覆盖新脚本。 */
  private SqlDraftRequest sqlCandidate(JobDraft job,ObjectNode sql,ObjectNode patched,String script) {
    var configuration=patched.deepCopy();
    configuration.withObject("/spec/job").remove("args");
    configuration.withObject("/metadata").put("name",sql.path("name").asText());
    var runtime=((ObjectNode)sql.path("runtime")).deepCopy();
    runtime.put("deploymentYaml",yaml.dump(configuration));
    return new SqlDraftRequest(sql.path("name").asText(),sql.path("description").asText(),script,runtime,sql.path("revision").asLong());
  }

  /** 生成可审阅方案，保留原文不保存到草稿；对比正文经过脱敏。 */
  public ObjectNode propose(JobDraft job,ObjectNode bundle,ObjectNode result,long version,String owner) {
    var changes=result.path("changes").isMissingNode()?mapper.createArrayNode():result.path("changes");
    if(!result.path("sql").isMissingNode() && !result.path("sql").isTextual()) fail("AI_INVALID_SQL","SQL 建议必须为完整文本");
    var sql=evidence.sql(job.id());
    String script=result.path("sql").asText("");
    if(!script.isBlank() && sql==null) fail("AI_INVALID_SQL","当前作业没有关联的 SQL 草稿");
    if(sql!=null && !script.isBlank()) {
      access.require("sql:"+sql.path("id").asText(),"edit");
      guard.requireSafe(sql.path("sql").asText()); validateSql(script);
    }
    var candidate=patched(job,changes);
    boolean sqlChanged=sql!=null && !script.isBlank() && !script.equals(sql.path("sql").asText());
    boolean yamlChanged=!candidate.equals(yaml.parse(job.yaml()));
    if(!yamlChanged && !sqlChanged) return null;
    String after=yaml.dump(candidate), check="YAML 本地结构检查通过；尚未执行集群 dry-run 或业务验证";
    if(sql!=null) {
      access.require("sql:"+sql.path("id").asText(),"read");
      var input=sqlCandidate(job,sql,candidate,sqlChanged?script:sql.path("sql").asText());
      var preview=sqlJobs.preview(input);
      after=preview.path("yaml").asText(); check=preview.path("message").asText()+"；未执行业务结果验证或集群 dry-run";
    }
    var plan=mapper.createObjectNode().put("jobId",job.id()).put("owner",owner).put("contentVersion",version)
        .put("jobCreatedAt",job.createdAt().toString()).put("beforeYaml",guard.redact(job.yaml())).put("afterYaml",guard.redact(after))
        .put("check",check).put("sqlChanged",sqlChanged).put("yamlChanged",yamlChanged);
    plan.set("changes",changes.deepCopy()); plan.set("baseline",bundle.path("baseline").deepCopy());
    for(String field:List.of("uid","resourceVersion","liveSpecHash","resourceExists","inconsistent"))
      if(bundle.has(field)) plan.set(field,bundle.get(field));
    if(sqlChanged) plan.put("sql",script).put("beforeSql",guard.redact(sql.path("sql").asText()));
    return plan;
  }

  /** 应用前再次核对草稿、SQL 版本与内容，不对新配置静默合并旧建议。 */
  public JobDraft requireCurrent(String jobId,ObjectNode plan) {
    var job=jobs.get(jobId);
    if(!evidence.matches(job,plan.path("baseline"))) conflict("SQL 或配置版本已经变化，请重新分析并审阅");
    return job;
  }

  /** 恢复方式属于显式操作参数，禁止在失败后自动降级为无状态。 */
  private void recovery(ObjectNode candidate,JsonNode live,ObjectNode input) {
    String mode=input.path("restoreMode").asText();
    if(!Set.of("stateless","savepoint","last-state").contains(mode) || !input.path("acceptImpact").asBoolean())
      fail("AI_CONFIRMATION_REQUIRED","请选择恢复方式并确认重启及处理进度影响");
    var job=candidate.withObject("/spec/job");job.put("state","running").put("upgradeMode",mode);
    job.remove("initialSavepointPath");
    if(mode.equals("savepoint")) {
      String path=input.path("savepointPath").asText();
      if(path.isBlank()) {
        if(!"RUNNING".equals(live.at("/status/jobStatus/state").asText()))
          fail("AI_SAVEPOINT_REQUIRED","当前作业未正常运行，请选择已确认保存点路径，或明确选择其他恢复方式");
      } else {
        guard.requireSafe(path);
        if(!storage.checkSnapshot(path).path("exists").asBoolean()) fail("AI_SAVEPOINT_UNAVAILABLE","保存点元数据不存在或不可读取");
        job.put("initialSavepointPath",path).put("savepointRedeployNonce",System.currentTimeMillis());
      }
    }
    if(mode.equals("last-state")) {
      var config=candidate.at("/spec/flinkConfiguration");
      String ha=config.path("high-availability.type").asText(config.path("high-availability").asText());
      if(ha.isBlank() || ha.equalsIgnoreCase("none")) fail("AI_HA_REQUIRED","last-state 需要已有高可用配置及可恢复状态，请核对后选择");
    }
  }

  /** 同号应用先记录意图，数据库保存原子完成，集群提交按独立阶段记录并核对原操作。 */
  public ObjectNode apply(String jobId,ObjectNode plan,ObjectNode input,Principal actor) {
    return applyScope(jobId,plan,input,actor,"ai_job_plans","ai_job_applications");
  }

  /** Harness 使用独立的方案与应用集合，复用完全相同的身份、幂等和运行边界。 */
  public ObjectNode applyHarness(String jobId,ObjectNode plan,ObjectNode input,Principal actor) {
    return applyScope(jobId,plan,input,actor,"ai_harness_plans","ai_harness_applications");
  }

  /** 新会话的 SQL 候选沿用原 AI 语句白名单和基础检查。 */
  public void validateHarnessSql(String script) { validateSql(script); }

  /** 集合名称由受信入口固定，不能由模型或浏览器提供。 */
  private ObjectNode applyScope(String jobId,ObjectNode plan,ObjectNode input,Principal actor,String plansCollection,String applicationsCollection) {
    access.require(jobId,"edit");
    String id=input.path("requestId").asText(), mode=input.path("mode").asText();
    if(!id.matches("[a-f0-9-]{36}") || !Set.of("save","restart").contains(mode)) fail("AI_INVALID_APPLICATION","应用请求标识或方式无效");
    String fingerprint=SqlDeploymentSource.hash(plan.path("id").asText()+input.toString());
    var old=records.find(applicationsCollection,id);
    if(old!=null) {
      requireOwner(old,jobId);
      if(!fingerprint.equals(old.path("fingerprint").asText())) conflict("应用请求号已用于其他内容");
      if(mode.equals("restart") && Set.of("SAVED","SUBMITTING","UNKNOWN").contains(old.path("state").asText())) return submit(old,actor,applicationsCollection);
      if(!old.path("state").asText().equals("SAVING")) return visible(old);
    }
    if(plan.hasNonNull("applicationId") && !id.equals(plan.path("applicationId").asText())) conflict("本方案已提交应用，请核对原应用记录；再次修改需要重新生成方案");
    var job=requireCurrent(jobId,plan);
    var sql=evidence.sql(jobId);
    if(sql!=null) access.require("sql:"+sql.path("id").asText(),"edit");
    var candidate=patched(job,plan.path("changes"));
    JsonNode live=null;
    if(mode.equals("restart")) {
      access.require(jobId,"operate");
      if(!plan.has("resourceExists") || plan.path("inconsistent").asBoolean()) conflict("运行证据不完整，请更新证据后再执行重启");
      live=evidence.resource(job);
      if(plan.path("resourceExists").asBoolean()!=!live.isMissingNode()
          || (!live.isMissingNode() && (!plan.path("uid").asText().equals(live.at("/metadata/uid").asText())
          || !plan.path("liveSpecHash").asText().equals(com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(live.path("spec"))))))
        conflict("集群资源身份或配置已经变化，请更新证据并重新审阅");
      recovery(candidate,live,input);
    }
    final var proposed=candidate;
    String script=plan.path("sqlChanged").asBoolean()?plan.path("sql").asText():sql==null?"":sql.path("sql").asText();
    if(plan.path("sqlChanged").asBoolean()) validateSql(script);
    if(mode.equals("restart")) {
      var forValidation=sql==null?candidate.deepCopy():yaml.parse(sqlJobs.preview(sqlCandidate(job,sql,candidate,script)).path("yaml").asText());
      // SQL 的恢复参数是本次运行意图，不交给运行配置映射器隐式丢弃。
      forValidation.withObject("/spec").set("job",candidate.path("spec").path("job").deepCopy());
      if(sql!=null) {
        var generated=yaml.parse(sqlJobs.preview(sqlCandidate(job,sql,candidate,script)).path("yaml").asText());
        forValidation.withObject("/spec/job").set("args",generated.at("/spec/job/args"));
      }
      forValidation.withObject("/metadata").put("name",job.deploymentName());
      operations.validate(yaml.dump(forValidation),job.deploymentName(),true);
    }
    var application=mapper.createObjectNode().put("jobId",jobId).put("owner",access.actor()).put("planId",plan.path("id").asText())
        .put("mode",mode).put("fingerprint",fingerprint).put("state","SAVING").put("message","正在保存已确认的修改");
    application.set("request",input.deepCopy());
    if(plansCollection.equals("ai_harness_plans"))application.put("sessionId",plan.path("sessionId").asText()).put("turnId",plan.path("turnId").asText());
    if(live!=null) application.put("expectedUid",live.isMissingNode()?"":live.at("/metadata/uid").asText())
        .put("expectedSpecHash",live.isMissingNode()?"":com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(live.path("spec")));
    if(old==null) {
      final var intent=application;
      application=transaction.execute(status->{
        var savedIntent=records.save(applicationsCollection,id,intent,0);
        records.save(plansCollection,plan.path("id").asText(),plan.put("applicationId",id),plan.path("revision").asLong());
        return savedIntent;
      });
    } else application=old;
    final var intent=application;
    try {
      var saved=transaction.execute(status->{
        requireCurrent(jobId,plan);
        JobDraft draft=job;
        if(sql!=null) {
          var savedSql=sqlJobs.save(sql.path("id").asText(),sqlCandidate(job,sql,proposed,script));
          if(mode.equals("restart")) draft=sqlJobs.publish(sql.path("id").asText(),savedSql.path("revision").asLong());
        } else draft=jobs.update(jobId,new DraftRequest(job.name(),job.description(),job.flinkVersion(),yaml.dump(proposed),job.revision()));
        if(mode.equals("restart") && sql!=null) {
          var runtime=yaml.parse(draft.yaml());
          for(String field:List.of("state","upgradeMode","initialSavepointPath","savepointRedeployNonce")) {
            if(proposed.at("/spec/job").has(field)) runtime.withObject("/spec/job").set(field,proposed.at("/spec/job").get(field));
            else runtime.withObject("/spec/job").remove(field);
          }
          draft=jobs.update(jobId,new DraftRequest(draft.name(),draft.description(),draft.flinkVersion(),yaml.dump(runtime),draft.revision()));
        }
        intent.put("savedJobRevision",draft.revision()).put("state","SAVED").put("message",mode.equals("save")?"草稿已保存，当前运行实例未变更":"草稿已保存并发布，尚未提交运行操作");
        records.save(applicationsCollection,id,intent,intent.path("revision").asLong());
        return draft;
      });
      application=records.require(applicationsCollection,id);
      if(mode.equals("restart")) {
        return submit(application,actor,applicationsCollection);
      }
    } catch(RuntimeException error) {
      application.put("state",application.has("operationId")?"UNKNOWN":"FAILED")
          .put("message",error instanceof BusinessException?error.getMessage():"应用未完成，请核对已保存草稿与原操作后重试");
    }
    return visible(records.save(applicationsCollection,id,application,application.path("revision").asLong()));
  }

  /** 恢复只重用同一个运行操作号与已保存修订，不重复保存或创建第二次部署。 */
  private ObjectNode submit(ObjectNode application,Principal actor,String applicationsCollection) {
    String id=application.path("id").asText(),jobId=application.path("jobId").asText();
    access.require(jobId,"operate");
    String operationId=UUID.nameUUIDFromBytes((id+":deploy").getBytes(StandardCharsets.UTF_8)).toString();
    application.put("operationId",operationId).put("state","SUBMITTING").put("message","草稿已保存并发布，正在核对并提交原运行操作");
    application=records.save(applicationsCollection,id,application,application.path("revision").asLong());
    try {
      var command=mapper.createObjectNode().put("revision",application.path("savedJobRevision").asLong()).put("requestId",operationId)
          .put("expectedUid",application.path("expectedUid").asText()).put("expectedSpecHash",application.path("expectedSpecHash").asText());
      var operation=operations.execute(jobId,"deploy",command,actor);
      application.put("state",operation.path("state").asText()).put("message",operation.path("message").asText());
    } catch(RuntimeException error) { application.put("state","UNKNOWN").put("message","运行提交尚未确认，请核对原操作号后继续；不会重放草稿保存"); }
    return visible(records.save(applicationsCollection,id,application,application.path("revision").asLong()));
  }

  /** 返回自己的应用记录；不暴露内部幂等指纹。 */
  public ObjectNode application(String jobId,String id) {
    return applicationScope(jobId,id,"ai_job_applications");
  }

  /** Harness 原号核对只访问它自己的应用记录。 */
  public ObjectNode harnessApplication(String jobId,String id) {
    return applicationScope(jobId,id,"ai_harness_applications");
  }

  /** 核对本地原运行操作，不隐式重新提交。 */
  private ObjectNode applicationScope(String jobId,String id,String collection) {
    var record=records.require(collection,id);requireOwner(record,jobId);
    if(record.has("operationId")) {
      var operation=records.find("operations",record.path("operationId").asText());
      if(operation!=null) record.put("state",operation.path("state").asText()).put("message",operation.path("message").asText());
    }
    if(record.path("state").asText().equals("SAVING")) record.put("message","原保存意图已登记但尚未确认完成，请核对并继续原应用；不会使用新请求号");
    return visible(record);
  }

  /** 应用记录与原作业及创建用户同时匹配，管理员不默认跨用户读取。 */
  private void requireOwner(ObjectNode record,String jobId) {
    if(!jobId.equals(record.path("jobId").asText()) || !access.actor().equals(record.path("owner").asText()))
      throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权查看或应用其他用户的修复方案");
  }

  /** 仅将可审阅结果返回页面。 */
  private ObjectNode visible(ObjectNode record) {var copy=record.deepCopy();copy.remove("fingerprint");return copy;}
  /** 参数不合法时使用稳定业务错误。 */
  private void fail(String code,String message) {throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
  /** 过期或同号冲突不能覆盖新内容。 */
  private void conflict(String message) {throw new BusinessException(HttpStatus.CONFLICT,"AI_PLAN_STALE",message);}
}
