package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.YamlService;
import com.flinkweb.service.job.management.JobOwnershipService;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** 仅在用户发起诊断时收集归属已核验的状态、事件、日志及配置投影。 */
@Component
public class AiJobEvidenceCollector {
  private final ObjectMapper mapper;
  private final KubernetesClient client;
  private final JobOwnershipService ownership;
  private final DiagnosticService diagnostics;
  private final YamlService yaml;
  private final AiContentGuard guard;
  private final AccessService access;
  private final RecordRepository records;

  /** 注入现有只读诊断与脱敏能力，模型不能指定任意集群目标。 */
  public AiJobEvidenceCollector(ObjectMapper mapper, KubernetesClient client, JobOwnershipService ownership,
      DiagnosticService diagnostics, YamlService yaml, AiContentGuard guard, AccessService access, RecordRepository records) {
    this.mapper=mapper; this.client=client; this.ownership=ownership; this.diagnostics=diagnostics;
    this.yaml=yaml; this.guard=guard; this.access=access; this.records=records;
  }

  /** 只通过明确关联找到 SQL 草稿；名称相同不能代替归属。 */
  public ObjectNode sql(String jobId) {
    return records.list("sql_jobs").stream().filter(s -> jobId.equals(s.path("jobId").asText())).findFirst().orElse(null);
  }

  /** 草稿与 SQL 各自绑定版本和摘要，重启不会改变会话的草稿生命周期。 */
  public ObjectNode baseline(JobDraft job) {
    var base=mapper.createObjectNode().put("jobRevision", job.revision()).put("jobCreatedAt",job.createdAt().toString())
        .put("yamlHash",SqlDeploymentSource.hash(job.yaml()));
    var sql=sql(job.id());
    if(sql!=null) base.put("sqlId",sql.path("id").asText()).put("sqlRevision",sql.path("revision").asLong())
        .put("sqlHash",SqlDeploymentSource.hash(sql.path("sql").asText()));
    return base;
  }

  /** JSON 入库后整数节点可能由 Long 变为 Int，版本比较按数值语义而非节点类型。 */
  public boolean matches(JobDraft job,JsonNode expected) {
    var current=baseline(job);
    for(String field:List.of("jobRevision","sqlRevision"))
      if(current.has(field)!=expected.has(field) || current.path(field).asLong(-1)!=expected.path(field).asLong(-1)) return false;
    for(String field:List.of("jobCreatedAt","yamlHash","sqlId","sqlHash"))
      if(!current.path(field).asText().equals(expected.path(field).asText())) return false;
    return true;
  }

  /** 读取真实声明前核对平台所有权；不存在与读取失败在上层明确区分。 */
  public JsonNode resource(JobDraft job) {
    var resource=client.call("GET",client.base()+"/"+job.deploymentName(),null);
    if(!resource.isMissingNode()) ownership.assertManaged(resource,job.id());
    return resource;
  }

  /** 每份证据固定身份与采集时间；限制配置、日志和总字节，不采集 env 或 Secret。 */
  public ObjectNode collect(JobDraft job, boolean includeLogs) {
    var bundle=mapper.createObjectNode().put("queriedAt",Instant.now().toString());
    var missing=bundle.putArray("unavailable");
    bundle.set("baseline",baseline(job));
    var source=yaml.parse(job.yaml());
    bundle.set("draftConfiguration",projection(source));
    var sql=sql(job.id());
    if(sql!=null && access.allowed("sql:"+sql.path("id").asText(),"read"))
      bundle.put("currentSql",sql.path("sql").asText());
    JsonNode live;
    try {
      live=resource(job);
      if(live.isMissingNode()) { bundle.put("resourceExists",false); missing.add("集群资源不存在，仅分析当前草稿与用户补充"); }
      else {
        bundle.put("resourceExists",true).put("uid",live.at("/metadata/uid").asText())
            .put("resourceVersion",live.at("/metadata/resourceVersion").asText())
            .put("liveSpecHash",com.flinkweb.service.job.creation.support.JobResourceFingerprint.hash(live.path("spec")));
        bundle.set("runtimeConfiguration",projection(live));
        bundle.put("runtimeError",live.at("/status/error").asText());
        bundle.put("jobState",live.at("/status/jobStatus/state").asText());
        bundle.put("lifecycleState",live.at("/status/lifecycleState").asText());
        var detail=diagnostics.inspect(job.id());
        bundle.set("diagnostics",detail);
        var logs=bundle.putArray("logs");
        if(includeLogs && access.allowed(job.id(),"operate")) {
          // 先在所有 Pod 中读取 Flink 主容器，避免两个名额被 JM 的下载器和主容器占满。
          outer: for(boolean main:List.of(true,false)) for(var pod:detail.path("pods")) for(var container:pod.path("states")) {
            if(main!=container.path("name").asText().equals("flink-main-container")) continue;
            if(logs.size()>=2) break outer;
            try {
              var log=diagnostics.log(job.id(),pod.path("name").asText(),container.path("name").asText(),120,container.path("restarts").asInt()>0);
              String text=log.path("text").asText();
              logs.addObject().put("pod",pod.path("name").asText()).put("container",container.path("name").asText())
                  .put("previous",container.path("restarts").asInt()>0).put("text",text.substring(Math.max(0,text.length()-8000)));
            } catch(RuntimeException error) { missing.add("部分容器日志不可用；不能据此排除该容器故障"); }
          }
        } else if(includeLogs) missing.add("当前权限不能读取容器日志");
        var after=resource(job);
        if(after.isMissingNode() || !live.at("/metadata/uid").equals(after.at("/metadata/uid"))
            || !live.path("spec").equals(after.path("spec"))) {
          bundle.put("inconsistent",true); missing.add("采集期间资源身份或配置发生变化，请更新证据后再应用");
        }
      }
    } catch(RuntimeException error) { missing.add("运行证据读取失败，本轮不能证明集群资源是否存在，也不能执行重启"); }
    var safe=(ObjectNode)guard.sanitize(bundle);
    if(safe.toString().length()>65000) {
      safe.remove("logs"); safe.remove("diagnostics");
      safe.withArray("unavailable").add("证据超过预算，已省略日志与事件详情，请缩小问题范围");
    }
    if(safe.toString().length()>70000) throw new com.flinkweb.exception.common.BusinessException(
        org.springframework.http.HttpStatus.BAD_REQUEST,"AI_INPUT_TOO_LARGE","必要配置与 SQL 超过证据预算，请缩小诊断范围");
    return safe;
  }

  /** 只发送可诊断的资源配置；移除模板、环境、参数及完整日志配置。 */
  private ObjectNode projection(JsonNode resource) {
    var result=mapper.createObjectNode();
    for(String key:List.of("flinkVersion","image","serviceAccount","mode"))
      if(resource.path("spec").has(key)) result.set(key,resource.path("spec").get(key));
    for(String component:List.of("jobManager","taskManager")) {
      var node=result.putObject(component);
      if(resource.at("/spec/"+component+"/resource").isObject()) node.set("resource",resource.at("/spec/"+component+"/resource"));
      if(resource.at("/spec/"+component).has("replicas")) node.set("replicas",resource.at("/spec/"+component+"/replicas"));
    }
    var config=result.putObject("flinkConfiguration");
    resource.at("/spec/flinkConfiguration").fields().forEachRemaining(e->{
      if(e.getKey().matches("(?i)(taskmanager\\.memory\\..*|jobmanager\\.memory\\..*|taskmanager.numberOfTaskSlots|parallelism.default|execution\\.checkpointing\\..*|restart-strategy\\..*|table\\.exec\\..*)")
          && !e.getKey().matches("(?i).*(password|secret|token|credential|key).*")) config.set(e.getKey(),e.getValue());
    });
    var specJob=resource.at("/spec/job");
    var job=result.putObject("job");
    for(String field:List.of("parallelism","upgradeMode","state")) if(specJob.has(field))job.set(field,specJob.get(field));
    return result;
  }
}
