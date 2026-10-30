package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** 按用户请求读取当前关联作业；状态、原操作核验和实际问题分别展示，不触发部署。 */
@Component
public class AiHarnessObservation {
  private final ObjectMapper mapper;
  private final JobService jobs;
  private final AiJobEvidenceCollector evidence;
  private final DiagnosticService diagnostics;
  private final AccessService access;
  private final AiContentGuard guard;

  /** 复用已有资源归属、诊断投影和脱敏边界。 */
  public AiHarnessObservation(ObjectMapper mapper,JobService jobs,AiJobEvidenceCollector evidence,
      DiagnosticService diagnostics,AccessService access,AiContentGuard guard){
    this.mapper=mapper;this.jobs=jobs;this.evidence=evidence;this.diagnostics=diagnostics;this.access=access;this.guard=guard;
  }

  /** 对话开始时取一次轻量状态，防止模型仅凭草稿或历史回复猜测是否运行。 */
  public ObjectNode snapshot(String jobId){return read(jobId,"",-1,false);}

  /** 状态查询工具增加当前 Pod、容器和近期事件问题，不读取原始日志。 */
  public ObjectNode status(String jobId){return read(jobId,"",-1,true);}

  /** 手动刷新核对原申请，同时返回失败、调度和不可用原因。 */
  public ObjectNode observe(String jobId,String operationId,long revision){return read(jobId,operationId,revision,true);}

  /** 聚合受限状态与诊断，读取失败表示未知，绝不退回历史成功。 */
  private ObjectNode read(String jobId,String operationId,long revision,boolean details){
    access.require(jobId,"read");var job=jobs.get(jobId);
    var out=mapper.createObjectNode().put("jobId",jobId).put("jobName",job.name()).put("observedAt",Instant.now().toString())
        .put("businessVerified",false).put("runningRevisionConfirmed",false).put("currentRunning",false);
    var issues=out.putArray("issues");JsonNode live;
    try{live=evidence.resource(job);}catch(RuntimeException failure){
      issue(issues,"读取状态","无法读取当前作业资源；不能以历史状态判断已经运行",false);
      return out.put("jobState","UNKNOWN").put("message","当前状态读取失败，请检查集群连接、权限和资源归属后重试");
    }
    if(live.isMissingNode()){
      issue(issues,"部署资源","当前未找到关联 FlinkDeployment；草稿或旧受理记录不代表运行中",false);
      return out.put("jobState","MISSING").put("message","当前部署资源不存在，尚不能确认运行");
    }
    String jobState=live.at("/status/jobStatus/state").asText("UNKNOWN"),manager=live.at("/status/jobManagerDeploymentStatus").asText("UNKNOWN");
    out.put("uid",live.at("/metadata/uid").asText()).put("jobState",jobState).put("lifecycleState",live.at("/status/lifecycleState").asText("UNKNOWN"))
        .put("jobManagerDeploymentStatus",manager).put("serviceAccount",live.at("/spec/serviceAccount").asText());
    long generation=live.at("/metadata/generation").asLong(-1);boolean reconciled=generation>=0&&live.at("/status/observedGeneration").asLong(-2)>=generation;
    if(!reconciled&&generation>=0)try{
      var previous=mapper.readTree(live.at("/status/reconciliationStatus/lastReconciledSpec").asText("{}"));
      reconciled=previous.at("/metadata/generation").asLong(-2)==generation||previous.path("spec").isObject()
          && com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(previous.path("spec")).equals(com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(live.path("spec")));
    }catch(Exception ignored){/* 无明确协调证据时保留未核验。 */}
    boolean matched=!operationId.isBlank()&&operationId.equals(live.at("/metadata/annotations/flink-web~1last-request").asText())&&job.revision()==revision;
    boolean unhealthy=!live.at("/status/error").asText().isBlank()||manager.equals("ERROR");
    if(unhealthy)issue(issues,"Operator","Operator 报告运行错误；完整脱敏原因见本次诊断结果",false);
    if(!reconciled)issue(issues,"协调状态","Operator 尚未确认当前资源代际，请稍后手动核验",false);
    if(live.at("/spec/serviceAccount").asText().isBlank())issue(issues,"运行配置","当前未显式配置 ServiceAccount；请核对实际 Pod 使用的账号及 RBAC",false);
    boolean consistent=true;
    if(details)try{
      var detail=diagnostics.inspect(jobId);String error=detail.path("error").asText();if(!error.isBlank())issue(issues,"Operator",error,false);
      for(var pod:detail.path("pods")){
        String name=pod.path("name").asText();
        if(!pod.path("reason").asText().isBlank()||!pod.path("message").asText().isBlank())issue(issues,"Pod "+name,pod.path("reason").asText()+" "+pod.path("message").asText(),false);
        for(var condition:pod.path("conditions"))if(condition.path("status").asText().equals("False")&&!condition.path("message").asText().isBlank())
          issue(issues,"Pod "+name,condition.path("reason").asText()+" "+condition.path("message").asText(),false);
        for(var container:pod.path("states"))if(!container.path("ready").asBoolean()&&(!container.path("reason").asText().isBlank()||container.path("exitCode").asInt()==137))
          issue(issues,"容器 "+container.path("name").asText(),container.path("reason").asText()+" "+container.path("message").asText()+(container.has("exitCode")?"；退出码 "+container.path("exitCode").asInt():""),false);
      }
      for(var event:detail.path("events"))if(event.path("type").asText().equals("Warning"))issue(issues,"近期事件 "+event.path("reason").asText(),event.path("message").asText(),true);
      var after=evidence.resource(job);
      consistent=!after.isMissingNode()&&after.at("/metadata/uid").equals(live.at("/metadata/uid"))&&after.path("spec").equals(live.path("spec"))
          &&after.at("/metadata/generation").equals(live.at("/metadata/generation"))&&after.at("/metadata/annotations/flink-web~1last-request").equals(live.at("/metadata/annotations/flink-web~1last-request"))
          &&after.at("/status/jobStatus/state").equals(live.at("/status/jobStatus/state"))&&after.at("/status/error").equals(live.at("/status/error"))&&jobs.get(jobId).revision()==job.revision();
      if(!consistent)issue(issues,"观测一致性","采集期间资源身份或配置变化，本次不能确认对应修订运行",false);
    }catch(RuntimeException failure){issue(issues,"诊断取证","Pod / 容器 / 事件读取不完整，当前只能报告资源状态；不能认定没有问题",false);out.put("diagnosticsIncomplete",true);}
    boolean running=consistent&&reconciled&&jobState.equals("RUNNING")&&!unhealthy;
    out.put("operationMatched",matched).put("currentRunning",running).put("runningRevisionConfirmed",matched&&running).put("hasProblems",!issues.isEmpty());
    String message=operationId.isBlank()?"本次实时查询：作业 "+jobState+"，生命周期 "+out.path("lifecycleState").asText():
        matched?"已观察原操作对应的资源；请结合当前状态及问题核验":"当前资源或修订不再匹配原申请，不能把当前状态当作原变更成功";
    if(!issues.isEmpty())message+="；发现问题或观测缺口，详见下方";
    return (ObjectNode)guard.sanitize(out.put("message",message));
  }

  /** 问题摘要有界且脱敏，近期事件与当前故障分开标记，避免把历史告警当作当前失败。 */
  private void issue(ArrayNode issues,String source,String message,boolean historical){
    if(message.isBlank()||issues.size()>=12)return;
    String safe=guard.redact(message.strip());if(safe.length()>1500)safe=safe.substring(0,1500)+"…";
    for(var previous:issues)if(previous.path("source").asText().equals(source)&&previous.path("message").asText().equals(safe))return;
    issues.addObject().put("source",source).put("message",safe).put("historical",historical);
  }
}
