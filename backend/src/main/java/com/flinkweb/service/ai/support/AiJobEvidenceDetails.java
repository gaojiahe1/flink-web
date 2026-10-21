package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.flink.AiFlinkEvidenceClient;
import com.flinkweb.client.kubernetes.KubernetesClient;
import com.flinkweb.entity.job.JobDraft;
import com.flinkweb.service.job.runtime.DiagnosticService;
import com.flinkweb.service.permission.AccessService;
import java.util.*;
import org.springframework.stereotype.Component;

/** 有界、按类别补充资源用量和Flink REST证据，局部不可用不会冒充空白正常。 */
@Component
public class AiJobEvidenceDetails {
  private final KubernetesClient client;
  private final DiagnosticService diagnostics;
  private final AiFlinkEvidenceClient flink;
  private final AiEvidenceDocuments documents;
  private final ObjectMapper mapper;
  private final AccessService access;

  /** 固定作业目标，复用Pod所有者链，外部模型不能指定网络端点。 */
  public AiJobEvidenceDetails(KubernetesClient client,DiagnosticService diagnostics,AiFlinkEvidenceClient flink,AiEvidenceDocuments documents,ObjectMapper mapper,AccessService access) {
    this.client=client;this.diagnostics=diagnostics;this.flink=flink;this.documents=documents;this.mapper=mapper;this.access=access;
  }

  /** 只查选中类别；Flink未启动时不连续请求所有REST端点。 */
  public void collect(JobDraft job,JsonNode live,ObjectNode bundle,String category) {
    var missing=bundle.withArray("unavailable");
    if(category.equals("pods") || category.equals("podMetrics")) {
      var pods=bundle.putArray("podDetails");int count=0;
      for(var row:bundle.path("diagnostics").path("pods")) {
        if(count++>=4){missing.add("本轮仅取前4个Pod详情，可根据具体Pod继续排查");break;}
        try {
          var pod=diagnostics.pod(job.id(),row.path("name").asText());
          if(pod==null || pod.isMissingNode())throw new IllegalStateException();
          var item=pods.addObject().put("name",row.path("name").asText());
          if(category.equals("pods"))item.put("yaml",documents.yaml(pod));
          else {
            var requests=item.putArray("configured");for(var container:pod.at("/spec/containers")) {
              var configured=requests.addObject().put("container",container.path("name").asText());configured.set("resources",container.path("resources"));
            }
            String nsPath=client.core().replace("/api/v1/","/apis/metrics.k8s.io/v1beta1/");
            var metrics=client.call("GET",nsPath+"/pods/"+row.path("name").asText(),null);
            if(metrics==null || !metrics.path("containers").isArray())throw new IllegalStateException();
            item.put("timestamp",metrics.path("timestamp").asText()).put("window",metrics.path("window").asText());item.set("usage",metrics.path("containers"));
          }
        } catch(RuntimeException error) { missing.add(category.equals("podMetrics")?"Pod实时用量不可用：可能未安装metrics-server、RBAC未授权或Pod已结束；不能视为使用量为0":"部分Pod配置不可用，可能资源已重建或无读取权限"); }
      }
      if(pods.isEmpty())missing.add("本轮没有可核验归属的Pod详情；Pod可能尚未创建或已经删除");
      return;
    }
    if(!category.startsWith("flink"))return;
    if(category.equals("flinkLogs") && !access.allowed(job.id(),"operate")){missing.add("读取Flink日志需要作业运维权限");return;}
    var result=bundle.putObject("flink").put("category",category);
    if(category.equals("flinkCheckpoints")) {
      var operator=result.putObject("operatorSnapshots");operator.set("savepoint",live.at("/status/jobStatus/savepointInfo"));operator.set("checkpoint",live.at("/status/jobStatus/checkpointInfo"));
    }
    try {result.set("overview",flink.read(job.deploymentName(),"overview"));}
    catch(RuntimeException error) {missing.add("Flink REST不可达，可能JM尚未就绪或已退出；请先看Pod、事件和容器日志");return;}
    String jobId=live.at("/status/jobStatus/jobId").asText();
    if(category.equals("flinkExceptions") || category.equals("flinkCheckpoints")) {
      if(!jobId.matches("[a-f0-9]{32}")){missing.add("Operator尚未提供当前Flink Job ID，无法可靠关联异常和Checkpoint");return;}
      read(result,category.equals("flinkExceptions")?"exceptions":"checkpoints",job,"jobs/"+jobId+"/"+(category.equals("flinkExceptions")?"exceptions?maxExceptions=20":"checkpoints"),missing);
    } else if(category.equals("flinkLogs")) {
      try {var log=flink.read(job.deploymentName(),"jobmanager/log");result.put("jobManagerLog",excerpt(log.path("text").asText())).put("logTruncated",log.path("truncated").asBoolean());}
      catch(RuntimeException error){missing.add("Flink JM日志不可用或超过传输预算，可改查受限容器日志");}
    } else if(category.equals("flinkMetrics")) {
      try {result.set("jobManager",jvm(job,"jobmanager/metrics"));}catch(RuntimeException error){missing.add("JobManager JVM / GC指标暂不可用");}
      try {
        var managers=flink.read(job.deploymentName(),"taskmanagers");var output=result.putArray("taskManagers");int count=0;
        for(var manager:managers.path("taskmanagers")) {
          if(count++>=2){missing.add("本轮JVM指标仅采集前2个TaskManager");break;}
          String id=manager.path("id").asText();
          if(!id.matches("[A-Za-z0-9_.:-]{1,200}"))continue;
          var tm=output.addObject().put("id",id);tm.set("metrics",jvm(job,"taskmanagers/"+id+"/metrics"));
        }
      }catch(RuntimeException error){missing.add("部分TaskManager JVM指标不可用或已退出");}
    }
  }

  /** 按当前JVM实际注册的指标选择Heap/NonHeap/Metaspace/GC/CPU/线程，避免假设GC名称。 */
  private JsonNode jvm(JobDraft job,String path) {
    var names=new ArrayList<String>();for(var metric:flink.read(job.deploymentName(),path)) {
      String id=metric.path("id").asText();
      if(id.matches("[A-Za-z0-9_.-]{1,200}") && id.matches("Status\\.JVM\\.(Memory\\..*|CPU\\..*|GarbageCollector\\..*|Threads\\..*)") && names.size()<32)names.add(id);
    }
    return names.isEmpty()?mapper.createArrayNode():flink.read(job.deploymentName(),path+"?get="+String.join(",",names));
  }

  /** 单个REST端点失败明确标记，不吞掉同组其它已取证数据。 */
  private void read(ObjectNode result,String name,JobDraft job,String path,com.fasterxml.jackson.databind.node.ArrayNode missing) {
    try {result.set(name,flink.read(job.deploymentName(),path));}catch(RuntimeException error){missing.add("Flink "+name+" 暂不可用、版本不支持或响应超过预算");}
  }

  /** 保留ERROR/WARN/异常栈附近内容，附带尾部；摘录不宣称是完整日志。 */
  public static String excerpt(String text) {
    var lines=text.split("\\R");var indices=new TreeSet<Integer>();
    for(int i=0;i<lines.length;i++)if(lines[i].matches("(?i).*(ERROR|WARN|Exception|Caused by|OOMKilled|OutOfMemory|Failed|Backpressure|Checkpoint).*"))for(int n=Math.max(0,i-2);n<=Math.min(lines.length-1,i+4);n++)indices.add(n);
    for(int i=Math.max(0,lines.length-20);i<lines.length;i++)indices.add(i);
    var output=new StringBuilder();for(int index:indices) {if(output.length()+lines[index].length()>10000)break;output.append(index+1).append(": ").append(lines[index]).append('\n');}
    return output.toString();
  }
}
