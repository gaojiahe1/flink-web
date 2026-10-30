package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.dto.sql.SqlDraftRequest;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.runtime.OperationService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.SqlJobService;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** SQL 候选的批准保存与首次运行，保存发布原子完成，集群提交按原号恢复。 */
@Component
public class AiHarnessSqlCoordinator {
  private static final String APPLICATIONS="ai_harness_applications",SESSIONS="ai_harness_sessions";
  private final ObjectMapper mapper;
  private final RecordRepository records;
  private final AccessService access;
  private final SqlJobService sql;
  private final OperationService operations;
  private final TransactionTemplate transaction;

  /** 复用 SQL 发布和作业运行边界，不向模型暴露写入工具。 */
  public AiHarnessSqlCoordinator(ObjectMapper mapper,RecordRepository records,AccessService access,SqlJobService sql,
      OperationService operations,org.springframework.transaction.PlatformTransactionManager manager){
    this.mapper=mapper;this.records=records;this.access=access;this.sql=sql;this.operations=operations;transaction=new TransactionTemplate(manager);
  }

  /** 同号冻结内容，先校验审阅配置，再原子创建绑定与发布，最后提交首次运行。 */
  public ObjectNode apply(ObjectNode session,ObjectNode plan,ObjectNode input,Principal actor){
    access.require("sql:"+session.path("sqlId").asText(),"edit");
    String mode=input.path("mode").asText(),id=input.path("requestId").asText();
    if(!Set.of("save","run").contains(mode))invalid("请选择仅保存或创建并运行");
    String fingerprint=SqlDeploymentSource.hash(plan.path("id").asText()+input.toString());
    var old=records.find(APPLICATIONS,id);
    if(old!=null){
      if(!access.actor().equals(old.path("owner").asText())||!session.path("id").asText().equals(old.path("sessionId").asText())||!fingerprint.equals(old.path("fingerprint").asText()))conflict("应用号已用于其他方案");
      return mode.equals("run")&&Set.of("SAVED","SUBMITTING","UNKNOWN").contains(old.path("state").asText())?submit(old,actor):old;
    }
    if(mode.equals("run")){
      if(!input.path("acceptImpact").asBoolean()||!plan.at("/execution/available").asBoolean())invalid("请审阅运行配置并确认首次无状态运行的资源与写入影响");
      var image=records.require("images",plan.at("/runtime/imageId").asText());
      if(!plan.path("imageFingerprint").asText().equals(SqlDeploymentSource.hash(image.toString())))conflict("运行镜像登记已变化，请重新生成方案");
      var preview=sql.preview(new SqlDraftRequest(plan.path("sqlName").asText(),"",plan.path("sql").asText(),(ObjectNode)plan.path("runtime"),plan.path("sqlRevision").asLong()+1));
      if(!preview.path("yaml").asText().equals(plan.path("runYaml").asText()))conflict("运行配置已变化，请重新生成并审阅方案");
      operations.validate(plan.path("runYaml").asText(),plan.path("sqlName").asText(),true);
    }
    var app=transaction.execute(status->{
      ObjectNode draft;
      if(plan.path("newDraft").asBoolean()){
        if(!session.path("sqlId").asText().isBlank())conflict("会话已绑定草稿，请重新审阅");
        draft=mapper.createObjectNode().put("id",plan.path("sqlName").asText()).put("name",plan.path("sqlName").asText()).put("description","").put("revision",0);
      }else{
        draft=sql.get(session.path("sqlId").asText());
        if(draft.path("revision").asLong()!=plan.path("sqlRevision").asLong()||!draft.path("createdAt").asText().equals(plan.path("sqlCreatedAt").asText())||!draft.path("sql").asText().equals(plan.path("beforeSql").asText()))conflict("SQL 已变化，请重新审阅");
      }
      if(mode.equals("run")&&!draft.path("jobId").asText().isBlank())conflict("已有发布作业，请核对恢复方式后在 SQL 工作台运行");
      var saved=sql.save(draft.path("id").asText(),new SqlDraftRequest(draft.path("name").asText(),draft.path("description").asText(),plan.path("sql").asText(),plan.path("runtime").isObject()?(ObjectNode)plan.path("runtime"):draft.path("runtime").isObject()?(ObjectNode)draft.path("runtime"):mapper.createObjectNode(),draft.path("revision").asLong()));
      var intent=mapper.createObjectNode().put("owner",access.actor()).put("sessionId",session.path("id").asText()).put("turnId",plan.path("turnId").asText()).put("mode",mode).put("state","SAVED").put("fingerprint",fingerprint).put("sqlId",saved.path("id").asText()).put("savedSqlRevision",saved.path("revision").asLong()).put("message","SQL 草稿已保存，未发布或运行");
      intent.set("request",input.deepCopy());
      if(mode.equals("run")){
        var job=sql.publish(saved.path("id").asText(),saved.path("revision").asLong());
        intent.put("jobId",job.id()).put("savedJobRevision",job.revision()).put("message","SQL 已保存并发布，尚未提交运行操作");session.put("jobId",job.id());
      }
      session.put("sqlId",saved.path("id").asText()).put("subjectCreatedAt",saved.path("createdAt").asText());
      if(plan.path("newDraft").asBoolean())session.put("title","开发 · "+saved.path("name").asText());
      records.save(SESSIONS,session.path("id").asText(),session,session.path("revision").asLong());
      return records.save(APPLICATIONS,id,intent,0);
    });
    return mode.equals("run")?submit(app,actor):app;
  }

  /** 保存发布完成后仅重用原操作号，遇到未知网络结果不重复保存或另建作业。 */
  private ObjectNode submit(ObjectNode app,Principal actor){
    access.require(app.path("jobId").asText(),"operate");
    String operationId=UUID.nameUUIDFromBytes((app.path("id").asText()+":deploy").getBytes(StandardCharsets.UTF_8)).toString();
    app.put("operationId",operationId).put("state","SUBMITTING").put("message","正在提交首次运行");
    app=records.save(APPLICATIONS,app.path("id").asText(),app,app.path("revision").asLong());
    try{
      var request=mapper.createObjectNode().put("revision",app.path("savedJobRevision").asLong()).put("requestId",operationId).put("expectedUid","").put("expectedSpecHash","");
      var result=operations.execute(app.path("jobId").asText(),"deploy",request,actor);
      app.put("state",result.path("state").asText()).put("message",result.path("message").asText());
    }catch(RuntimeException failure){app.put("state","UNKNOWN").put("message","SQL 已保存并发布，运行提交结果待核对；请继续原申请，不要重复创建");}
    return records.save(APPLICATIONS,app.path("id").asText(),app,app.path("revision").asLong());
  }

  /** 不合规输入在保存或集群写入前拒绝。 */
  private void invalid(String message){throw new BusinessException(HttpStatus.BAD_REQUEST,"HARNESS_INVALID_REQUEST",message);}
  /** 身份或版本冲突需要重新审阅，不静默覆盖用户修改。 */
  private void conflict(String message){throw new BusinessException(HttpStatus.CONFLICT,"HARNESS_CONFLICT",message);}
}
