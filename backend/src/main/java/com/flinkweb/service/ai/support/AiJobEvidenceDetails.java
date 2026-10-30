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
      try {result.set("jobManager",jvm(job,"jobmanager/metrics",missing,"JobManager"));}catch(RuntimeException error){missing.add("JobManager JVM / GC指标读取失败或暂不可用，不能视为正常或使用量为0");}
      try {
        var managers=flink.read(job.deploymentName(),"taskmanagers");var output=result.putArray("taskManagers");int count=0;
        for(var manager:managers.path("taskmanagers")) {
          if(count++>=2){missing.add("本轮JVM指标仅采集前2个TaskManager");break;}
          String id=manager.path("id").asText();
          if(!id.matches("[A-Za-z0-9_.:-]{1,200}"))continue;
          var tm=output.addObject().put("id",id);
          try{tm.set("metrics",jvm(job,"taskmanagers/"+id+"/metrics",missing,"TaskManager "+id));}
          catch(RuntimeException error){missing.add("TaskManager "+id+" JVM指标读取失败或已退出；不能视为使用量为0");}
        }
      }catch(RuntimeException error){missing.add("部分TaskManager JVM指标不可用或已退出");}
    }
  }

  /** 按当前JVM实际注册的指标选择Heap/NonHeap/Metaspace/GC/CPU/线程，避免假设GC名称。 */
  private JsonNode jvm(JobDraft job,String path,com.fasterxml.jackson.databind.node.ArrayNode missing,String label) {
    var registered=flink.read(job.deploymentName(),path);
    if(!registered.isArray())throw new IllegalStateException("Invalid metric list");
    if(registered.isEmpty()) {
      missing.add(label+" JVM指标列表为空，可能尚未注册或版本未提供；不能视为正常或使用量为0");
      return mapper.createArrayNode();
    }
    var names=new ArrayList<String>();for(var metric:registered) {
      String id=metric.path("id").asText();
      if(id.matches("[A-Za-z0-9_.-]{1,200}") && id.matches("Status\\.JVM\\.(Memory\\..*|CPU\\..*|GarbageCollector\\..*|Threads\\..*)") && names.size()<32)names.add(id);
    }
    if(names.isEmpty()) {
      missing.add(label+" 已注册指标中没有匹配的JVM / GC指标，可能版本或命名不同；不能视为正常或使用量为0");
      return mapper.createArrayNode();
    }
    var values=flink.read(job.deploymentName(),path+"?get="+String.join(",",names));
    if(!values.isArray())throw new IllegalStateException("Invalid metric values");
    if(values.isEmpty())missing.add(label+" JVM指标值响应为空，可能已退出或采集暂不可用；不能视为正常或使用量为0");
    return values;
  }

  /** 单个REST端点失败明确标记，不吞掉同组其它已取证数据。 */
  private void read(ObjectNode result,String name,JobDraft job,String path,com.fasterxml.jackson.databind.node.ArrayNode missing) {
    try {result.set(name,flink.read(job.deploymentName(),path));}catch(RuntimeException error){missing.add("Flink "+name+" 暂不可用、版本不支持或响应超过预算");}
  }

  /** 保留ERROR/WARN/异常栈附近内容，附带尾部；摘录不宣称是完整日志。 */
  public static String excerpt(String text) {
    if(text.isEmpty())return "";
    var lines=text.split("\\R");var indices=new TreeSet<Integer>();
    for(int i=0;i<lines.length;i++)if(lines[i].matches("(?i).*(ERROR|WARN|Exception|Caused by|OOMKilled|OutOfMemory|Failed|Backpressure|Checkpoint).*"))for(int n=Math.max(0,i-2);n<=Math.min(lines.length-1,i+4);n++)indices.add(n);
    // 最新尾部先占预算，避免早期密集异常耗尽摘录后丢失当前故障进展。
    int tailStart=Math.max(0,lines.length-20),remaining=4000;
    var tail=new ArrayDeque<String>();
    for(int i=lines.length-1;i>=tailStart && remaining>=40;i--) {
      String line=excerptLine(i+1,lines[i],Math.min(1000,remaining));
      tail.addFirst(line);remaining-=line.length();
    }
    String latest=String.join("",tail), separator="… [以下为最新尾部摘录] …\n";
    var output=new StringBuilder();int budget=10000-latest.length()-separator.length();
    for(int index:indices) {
      if(index>=tailStart || budget-output.length()<40)break;
      output.append(excerptLine(index+1,lines[index],Math.min(1500,budget-output.length())));
    }
    if(!output.isEmpty())output.append(separator);
    return output.append(latest).toString();
  }

  /** 对超长单行保留开头和最新结尾，预算包含行号、换行和省略标记。 */
  private static String excerptLine(int number,String text,int budget) {
    String prefix=number+": ",marker=" …[单行省略]… ";
    if(prefix.length()+text.length()+1<=budget)return prefix+text+"\n";
    int size=Math.max(0,budget-prefix.length()-marker.length()-1),head=size/2,tail=size-head;
    if(head>0 && Character.isHighSurrogate(text.charAt(head-1)))head--;
    int start=text.length()-tail;
    if(start<text.length() && Character.isLowSurrogate(text.charAt(start)))start++;
    return prefix+text.substring(0,head)+marker+text.substring(start)+"\n";
  }
}
