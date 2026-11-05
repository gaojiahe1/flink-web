package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.client.flink.AiFlinkEvidenceClient;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.job.creation.JobService;
import com.flinkweb.service.job.runtime.*;
import com.flinkweb.service.permission.AccessService;
import com.flinkweb.service.session.SessionClusterService;
import com.flinkweb.service.sql.*;
import com.flinkweb.service.sql.catalog.FlinkCatalogService;
import com.flinkweb.exception.common.BusinessException;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Harness 与 MCP 共用的受控只读能力目录；发现、参数验证和执行同源。 */
@Component
public class AiHarnessCapabilities {
  private final ObjectMapper mapper;
  private final AccessService access;
  private final JobService jobs;
  private final SqlJobService sql;
  private final SqlExecutionService executions;
  private final FlinkCatalogService catalogs;
  private final SessionClusterService sessions;
  private final DiagnosticService diagnostics;
  private final SnapshotService snapshots;
  private final OperationService operations;
  private final AiFlinkEvidenceClient flink;
  private final AiJobEvidenceCollector evidence;
  private final AiHarnessObservation observation;
  private final RecordRepository records;
  private final AiContentGuard guard;
  private final AiHarnessPreferences preferences;
  private final LinkedHashMap<String,ObjectNode> definitions=new LinkedHashMap<>();
  private static final Map<String,String> PROMPTS=Map.of(
      "develop_sql","先读取实际Catalog和表结构，再根据业务目标生成SQL候选；检查实际版本，必要缺口一次补齐；保存和运行需审阅。",
      "diagnose_job","根据当前运行身份读取状态、异常及相关证据，区分事实、假设和未知；按当前账号权限直接读取脱敏日志，无需重复对话授权。",
      "diagnose_checkpoint_failure","先读取Checkpoint概览及配置，再下钻失败或未完成的Checkpoint、算子和子任务，关联同一时间的JM/TM日志；区分背压、存储、超时与任务失败。",
      "diagnose_restart","读取Pod事件、退出状态和current/previous日志，核对重启次数及实例身份；137不能单独证明OOM。",
      "review_restore_source","读取Savepoint/Checkpoint台账与元数据检查结果，核对来源、完成状态和版本；元数据存在不能证明可成功恢复。",
      "verify_deployment","根据原操作号和目标修订核对受理、实际运行及业务结果，分别报告，不把ACCEPTED等同成功。",
      "review_resource_change","先取当前配置和资源证据，生成最小差异及影响；审阅并选择恢复方式后才应用。",
      "compare_sql_versions","读取用户指定的SQL修订，比较语义、运行配置和状态兼容影响，不自动恢复或发布。",
      "diagnose_backpressure","先用flink_job_plan发现vertex ID，再读取flink_vertex_backpressure/metrics及Checkpoint和日志；缺失指标不能记作零。");

  /** 领域服务保留自身身份检查；适配层额外落实原控制器角色约束。 */
  public AiHarnessCapabilities(ObjectMapper mapper,AccessService access,JobService jobs,SqlJobService sql,SqlExecutionService executions,FlinkCatalogService catalogs,
      SessionClusterService sessions,DiagnosticService diagnostics,SnapshotService snapshots,OperationService operations,
      AiFlinkEvidenceClient flink,AiJobEvidenceCollector evidence,AiHarnessObservation observation,RecordRepository records,AiContentGuard guard,AiHarnessPreferences preferences) {
    this.mapper=mapper;this.access=access;this.jobs=jobs;this.sql=sql;this.executions=executions;this.catalogs=catalogs;this.sessions=sessions;this.diagnostics=diagnostics;
    this.snapshots=snapshots;this.operations=operations;this.flink=flink;this.evidence=evidence;this.observation=observation;this.records=records;this.guard=guard;this.preferences=preferences;
    define("job_search","查找已登记作业，不查询集群。","query?","offset?:integer");
    define("sql_search","查找SQL草稿。","query?","offset?:integer");
    define("job_config_read","读取当前作业草稿配置，不代表集群配置。","jobId?");
    define("job_operation_history","读取作业操作记录。","jobId?");
    define("job_status_read","按需读取指定可见作业当前状态。","jobId?");
    define("job_pods_read","读取Pod、初始化/主容器状态和退出原因。","jobId?","pod?");
    define("job_events_read","读取当前作业及Pod的Kubernetes事件。","jobId?");
    define("sql_draft_read","读取SQL草稿及保存修订。","sqlId");
    define("sql_versions_read","读取SQL保存历史，不恢复历史。","sqlId");
    define("sql_runtime_preview","预览已保存SQL的运行配置，不保存或运行。","sqlId");
    define("sql_diff","比较两个保存版本；空version表示当前草稿。","sqlId","beforeVersion","afterVersion?");
    define("sql_verification_status","读取指定SQL验证任务状态。","verificationId");
    define("session_config_read","读取已登记Session配置，不能证明运行配置。","sessionId");
    define("artifact_detail","读取制品安全元数据，不返回下载凭据。","artifactId");
    define("restore_history_read","读取Operator当前上报的恢复信息和平台历史操作，不补造完整历史。","jobId?");
    define("catalog_list","列出已登记Catalog，不探测远端。","offset?:integer");
    define("catalog_metadata","读取Catalog的库、表、字段；空database/table逐层浏览。","catalogId","database?","table?");
    define("catalog_functions","读取Catalog函数，不执行函数。","catalogId","database?","name?");
    define("catalog_snippet","读取Catalog初始化SQL及密钥引用。","catalogId","database?");
    define("session_list","列出Session配置摘要，不刷新集群。","offset?:integer");
    define("session_status","按需查询Session状态。","sessionId");
    define("session_diagnostics","查询Session的Pod和事件。","sessionId");
    define("session_logs_read","读取已核验Session日志，沿用管理员/运维角色，无需重复对话授权。","sessionId","pod","container","lines?:integer","previous?:boolean");
    define("runtime_image_list","读取已登记运行镜像及版本。","offset?:integer");
    define("artifact_list","读取制品名称和类型摘要。","offset?:integer");
    define("log_sources_list","发现作业可读Pod和容器日志来源。","jobId?");
    define("logs_read","读取指定Pod/容器current或previous日志，最多10000行；保存短期私有快照并返回首页，可用logs_page继续。","jobId?","pod","container","lines?:integer","previous?:boolean");
    define("logs_page","继续读取短期私有日志快照；offset是字符偏移。","logId","offset?:integer");
    define("logs_release","释放本用户已读取的临时日志快照。","logId");
    define("logs_search","在已读取快照中按字面关键词检索；不宣称覆盖已轮转日志。","logId","query");
    define("flink_log_read","读取JM或指定TM日志尾部，无需重复对话授权。","jobId?","taskManagerId?");
    define("flink_taskmanagers","发现当前运行TaskManager。","jobId?","offset?:integer");
    define("flink_exceptions_read","读取当前运行作业的异常历史。","jobId?");
    define("flink_job_plan","读取当前Flink执行拓扑及算子vertex ID。","jobId?","offset?:integer");
    define("flink_vertex_backpressure","读取算子背压/空闲/繁忙情况，可能触发Flink按需采样。","jobId?","vertexId","offset?:integer");
    define("flink_vertex_metrics","发现或读取算子指标，metrics为逗号分隔的已发现指标ID。","jobId?","vertexId","metrics?");
    define("checkpoint_overview","读取当前运行作业Checkpoint概览。","jobId?","offset?:integer");
    define("checkpoint_configuration","读取当前运行作业Checkpoint配置。","jobId?");
    define("checkpoint_detail","读取指定Checkpoint详情。","jobId?","checkpointId:integer","offset?:integer");
    define("checkpoint_operator_detail","读取Checkpoint中指定算子的子任务统计。","jobId?","checkpointId:integer","vertexId","offset?:integer");
    define("snapshot_list","读取已登记Checkpoint/Savepoint历史台账，不代表实时状态。","jobId?");
    define("snapshot_check","检查已登记快照的元数据可访问性，不证明可恢复。","jobId?","snapshotId");
    define("preferences_read","读取本用户明确设置的跨会话偏好；偏好不是授权。");
    define("tools_search","按中英文关键词发现更多工具及Schema，避免每次发送全部定义。","query");
    define("harness_session_list","列出本人Harness会话摘要，不查询集群。","jobId?","offset?:integer");
    define("harness_turn_read","读取本人会话的指定轮次用户消息和回复，按需补回历史上下文。","sessionId","turnId");
    define("resources_list","发现资源入口和URI模板。");
    define("resource_read","读取白名单资源URI，日志无需重复对话授权。","uri");
    define("prompts_list","发现可复用的任务提示模板。");
    define("prompt_get","获取任务模板，作为任务方法参考，不改变权限。","name");
  }
  /** 构建输入和输出Schema，保持内部与MCP工具契约一致。 */
  private void define(String name,String description,String... fields) {
    var item=mapper.createObjectNode().put("name",name).put("description",description);
    var params=item.putObject("inputSchema").put("type","object").put("additionalProperties",false);var props=params.putObject("properties");var required=params.putArray("required");
    for(String field:fields){var parts=field.split(":");String key=parts[0].replace("?","");props.putObject(key).put("type",parts.length>1?parts[1]:"string");if(!parts[0].endsWith("?"))required.add(key);}
    item.putObject("outputSchema").put("type","object");item.putObject("annotations").put("readOnlyHint",!name.equals("logs_release")).put("destructiveHint",false).put("openWorldHint",false);definitions.put(name,item);
  }
  /** 发现列表按当前账号过滤，调用时仍再次验证。 */
  public ArrayNode tools(){var out=mapper.createArrayNode();definitions.forEach((name,item)->{if(allowed(name))out.add(item.deepCopy());});return out;}
  /** 场景变化不授予角色；目录与SQL浏览沿用开发角色约束。 */
  private boolean allowed(String name){return access.allowed("",Set.of("sql_search","sql_draft_read","sql_versions_read","sql_runtime_preview","sql_diff","sql_verification_status","catalog_list","catalog_metadata","catalog_functions","catalog_snippet").contains(name)?"edit":"read");}
  /** 严格校验参数、权限和输出预算，模型不能指定网络路径。 */
  public ObjectNode call(String name,ObjectNode args,String defaultJob,boolean allowLogs,String sessionId){
    var spec=definitions.get(name);if(spec==null)fail("HARNESS_TOOL_UNSUPPORTED","未登记该工具");
    if(!allowed(name))throw new BusinessException(HttpStatus.FORBIDDEN,"HARNESS_TOOL_DENIED","当前角色不能使用该工具");
    var props=spec.at("/inputSchema/properties");args.fields().forEachRemaining(f->{var type=props.path(f.getKey()).path("type").asText();if(type.isEmpty()||type.equals("string")&&(!f.getValue().isTextual()||f.getValue().asText().length()>2000)||type.equals("integer")&&(!f.getValue().isIntegralNumber()||!f.getValue().canConvertToLong()||type.equals("integer")&&!f.getKey().equals("checkpointId")&&!f.getValue().canConvertToInt())||type.equals("boolean")&&!f.getValue().isBoolean())fail("HARNESS_INVALID_TOOL","工具参数类型或字段无效");});
    for(var required:spec.at("/inputSchema/required"))if(!args.has(required.asText())||args.path(required.asText()).isTextual()&&args.path(required.asText()).asText().isBlank())fail("HARNESS_INVALID_TOOL","缺少工具必填参数");
    guard.requireSafe(args.toString());
    String jobId=args.path("jobId").asText(defaultJob);ObjectNode out;
    switch(name){
      case "job_search" -> {var rows=mapper.createArrayNode();for(var job:jobs.list())if(job.name().contains(args.path("query").asText()))rows.addObject().put("id",job.id()).put("name",job.name()).put("revision",job.revision());out=page(rows,args);}
      case "sql_search" -> out=page(summaries(sql.list(),args.path("query").asText()),args);
      case "job_config_read" -> {access.require(jobId,"read");out=evidence.current(jobs.get(jobId));}
      case "job_operation_history" -> {access.require(jobId,"read");jobs.get(jobId);out=mapper.createObjectNode();out.set("items",mapper.valueToTree(operations.history(jobId)));}
      case "job_status_read" -> {access.require(jobId,"read");out=observation.status(jobId);}
      case "job_pods_read","job_events_read" -> {access.require(jobId,"read");var data=diagnostics.inspect(jobId);out=mapper.createObjectNode();String field=name.equals("job_pods_read")?"pods":"events";out.set(field,data.path(field).deepCopy());if(data.has("error"))out.set("deploymentError",data.get("error"));if(field.equals("pods")&&!args.path("pod").asText().isBlank()){var rows=out.putArray("pods");for(var pod:data.path("pods"))if(args.path("pod").asText().equals(pod.path("name").asText()))rows.add(pod);if(rows.isEmpty())fail("HARNESS_POD_NOT_FOUND","当前作业未发现该Pod");}}
      case "sql_draft_read" -> {access.require("sql:"+args.path("sqlId").asText(),"edit");out=sql.get(args.path("sqlId").asText());}
      case "sql_versions_read" -> {out=mapper.createObjectNode();out.set("items",mapper.valueToTree(sql.versions(args.path("sqlId").asText())));}
      case "sql_runtime_preview" -> {var draft=sql.get(args.path("sqlId").asText());out=sql.preview(new com.flinkweb.dto.sql.SqlDraftRequest(draft.path("name").asText(),draft.path("description").asText(),draft.path("sql").asText(),(ObjectNode)draft.path("runtime"),draft.path("revision").asLong()));}
      case "sql_diff" -> {String id=args.path("sqlId").asText();var versions=sql.versions(id);var before=versions.stream().filter(v->v.path("id").asText().equals(args.path("beforeVersion").asText())).findFirst().orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","SQL历史版本不存在"));ObjectNode after=sql.get(id);if(!args.path("afterVersion").asText().isBlank())after=versions.stream().filter(v->v.path("id").asText().equals(args.path("afterVersion").asText())).findFirst().orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","SQL历史版本不存在"));out=mapper.createObjectNode();out.set("before",before);out.set("after",after);}
      case "sql_verification_status" -> {var run=records.require("sql_validations",args.path("verificationId").asText());access.require("sql:"+run.path("sqlId").asText(),"edit");out=executions.status(args.path("verificationId").asText());}
      case "session_config_read" -> {out=sessions.list().stream().filter(v->v.path("id").asText().equals(args.path("sessionId").asText())).findFirst().orElseThrow(()->new BusinessException(HttpStatus.NOT_FOUND,"NOT_FOUND","Session不存在"));var safe=mapper.createObjectNode();for(String key:List.of("id","name","revision","desiredState","deploymentName"))if(out.has(key))safe.set(key,out.get(key));var runtime=out.path("runtime");var projected=mapper.createObjectNode();for(String key:List.of("imageId","parallelism","slots","serviceAccount","jobManager","taskManager","upgradeMode","dependencyArtifactIds","catalogIds","catalogId"))if(runtime.has(key))projected.set(key,runtime.get(key));safe.set("runtime",projected);safe.put("note","配置安全投影；环境变量、原始Flink属性和Pod模板未外发");out=safe;}
      case "artifact_detail" -> {var row=records.require("artifacts",args.path("artifactId").asText());out=mapper.createObjectNode();for(String key:List.of("id","name","type","size","sha256","filename","revision"))if(row.has(key))out.set(key,row.get(key));}
      case "restore_history_read" -> {access.require(jobId,"read");var live=evidence.resource(jobs.get(jobId));out=mapper.createObjectNode().put("scope","Operator当前上报及本地已登记操作，不承诺完整恢复历史");out.set("restore",live.at("/status/jobStatus/checkpointInfo"));out.set("savepoint",live.at("/status/jobStatus/savepointInfo"));out.set("operations",mapper.valueToTree(operations.history(jobId)));}
      case "catalog_list" -> out=page(summaries(catalogs.list(),""),args);
      case "catalog_metadata" -> out=catalogs.metadata(args.path("catalogId").asText(),args.path("database").asText(),args.path("table").asText());
      case "catalog_functions" -> out=catalogs.functions(args.path("catalogId").asText(),args.path("database").asText(),args.path("name").asText());
      case "catalog_snippet" -> out=catalogs.snippet(args.path("catalogId").asText(),args.path("database").asText());
      case "session_list" -> out=page(summaries(sessions.list(),""),args);
      case "session_status" -> out=sessions.status(args.path("sessionId").asText());
      case "session_diagnostics" -> out=diagnostics.inspectSession(args.path("sessionId").asText());
      case "session_logs_read" -> {requireSessionLogs();out=logSnapshot(diagnostics.sessionLog(args.path("sessionId").asText(),args.path("pod").asText(),args.path("container").asText(),lines(args),args.path("previous").asBoolean()),"",args.path("sessionId").asText(),sessionId);}
      case "runtime_image_list" -> {var rows=mapper.createArrayNode();for(var row:records.list("images"))rows.addObject().put("id",row.path("id").asText()).put("name",row.path("name").asText()).put("flinkVersion",row.path("flinkVersion").asText()).put("image",row.path("repository").asText()+":"+row.path("version").asText());out=page(rows,args);}
      case "artifact_list" -> out=page(summaries(records.list("artifacts"),""),args);
      case "log_sources_list" -> {access.require(jobId,"operate");out=diagnostics.inspect(jobId).deepCopy();if(out.has("error"))out.set("deploymentError",out.remove("error"));}
      case "logs_read" -> {access.require(jobId,"operate");out=logSnapshot(diagnostics.log(jobId,args.path("pod").asText(),args.path("container").asText(),lines(args),args.path("previous").asBoolean()),jobId,"",sessionId);}
      case "logs_page","logs_search","logs_release" -> out=storedLog(name,args);
      case "flink_log_read","flink_taskmanagers","flink_exceptions_read","flink_job_plan","flink_vertex_backpressure","flink_vertex_metrics","checkpoint_overview","checkpoint_configuration","checkpoint_detail","checkpoint_operator_detail" -> {out=runtime(name,args,jobId);if(name.equals("flink_log_read"))out=logSnapshot(out,jobId,"",sessionId);}
      case "snapshot_list" -> {access.require(jobId,"read");jobs.get(jobId);out=mapper.createObjectNode().put("source","LOCAL_LEDGER").put("note","历史台账，不是本次实时观测");out.set("items",mapper.valueToTree(snapshots.list(jobId)));}
      case "snapshot_check" -> {access.require(jobId,"operate");jobs.get(jobId);out=snapshots.check(jobId,args.path("snapshotId").asText());}
      case "preferences_read" -> out=preferences.get();
      case "tools_search" -> out=searchTools(args.path("query").asText());
      case "harness_session_list" -> {var rows=mapper.createArrayNode();for(var row:records.list("ai_harness_sessions"))if(access.actor().equals(row.path("owner").asText())&&(jobId.isBlank()||jobId.equals(row.path("jobId").asText()))){var item=rows.addObject();for(String key:List.of("id","title","kind","jobId","sqlId","revision"))if(row.has(key))item.set(key,row.get(key));}out=page(rows,args);}
      case "harness_turn_read" -> {var session=records.require("ai_harness_sessions",args.path("sessionId").asText());var turn=records.require("ai_harness_turns",args.path("turnId").asText());if(!access.actor().equals(session.path("owner").asText())||!access.actor().equals(turn.path("owner").asText())||!session.path("id").equals(turn.path("sessionId"))||!session.path("epoch").equals(turn.path("epoch")))throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权读取该私有轮次");if(session.path("kind").asText().equals("sql")){String sid=session.path("sqlId").asText();access.require("sql:"+sid,"edit");if(!sid.isBlank()&&!sql.get(sid).path("createdAt").equals(session.path("subjectCreatedAt")))fail("HARNESS_CONTEXT_CHANGED","SQL生命周期已变化");}else if(!session.path("jobId").asText().isBlank()){access.require(session.path("jobId").asText(),"read");if(!jobs.get(session.path("jobId").asText()).createdAt().toString().equals(session.path("subjectCreatedAt").asText()))fail("HARNESS_CONTEXT_CHANGED","作业生命周期已变化");}out=mapper.createObjectNode();for(String key:List.of("id","sessionId","sequence","state","content","reply","requirements"))if(turn.has(key))out.set(key,turn.get(key));}
      case "resources_list" -> out=resources();
      case "resource_read" -> out=resource(args.path("uri").asText(),allowLogs,sessionId);
      case "prompts_list" -> out=prompts();
      case "prompt_get" -> out=prompt(args.path("name").asText());
      default -> throw new IllegalStateException("工具缺少处理器");
    }
    out=(ObjectNode)guard.sanitize(protect(out));if(out.toString().length()>85000)fail("HARNESS_RESULT_TOO_LARGE","结果超过预算，请缩小查询范围");out.put("queriedAt",Instant.now().toString());return out;
  }
  /** 搜索不扩大权限，模型下一步才能使用按需发现的扩展工具。 */
  private ObjectNode searchTools(String query){
    if(query.isBlank())fail("HARNESS_INVALID_TOOL","请提供工具关键词");String expanded=query.toLowerCase(Locale.ROOT).replace("日志","logs log").replace("检查点","checkpoint").replace("背压","backpressure").replace("指标","metrics").replace("事件","events").replace("容器","pod").replace("历史","history turn");
    var out=mapper.createObjectNode();var items=out.putArray("tools");var names=out.putArray("discoveredTools");
    for(var spec:tools()){String hay=(spec.path("name").asText()+" "+spec.path("description").asText()).toLowerCase(Locale.ROOT);boolean match=false;for(String term:expanded.split("[\\s,，、]+"))if(!term.isBlank()&&hay.contains(term))match=true;if(match){items.add(spec);names.add(spec.path("name").asText());}}
    out.put("total",items.size());return out;
  }
  /** 敏感字段按名称遮蔽，防止仅按字符串匹配遗漏未登记字面密钥。 */
  private JsonNode protect(JsonNode value){
    var copy=value.deepCopy();if(copy instanceof ObjectNode object){
      var names=new ArrayList<String>();object.fieldNames().forEachRemaining(names::add);
      boolean secretEnv=object.path("name").asText().matches("(?i).*(PASSWORD|PASSWD|SECRET|TOKEN|ACCESS_KEY|API_KEY).*?");
      for(String name:names){var item=object.get(name);boolean secret=name.matches("(?i).*(?:password|passwd|secret[_.-]?key|access[_.-]?key|api[_.-]?key|authorization|encryptedValue|encryptedKey).*|(?i)token")||secretEnv&&name.equals("value");
        if(secret&&!item.asText().startsWith("{{")&&!item.asText().startsWith("${"))object.put(name,"[REDACTED]");else object.set(name,protect(item));
      }
    }else if(copy instanceof ArrayNode array)for(int n=0;n<array.size();n++)array.set(n,protect(array.get(n)));return copy;
  }
  /** 列表仅投影安全摘要，避免将登记中的连接属性整体外发。 */
  private ArrayNode summaries(List<ObjectNode> values,String query){var rows=mapper.createArrayNode();for(var value:values)if(value.path("name").asText().contains(query)){var row=rows.addObject();for(String key:List.of("id","name","revision","type","flinkVersion","jobId"))if(value.has(key))row.set(key,value.get(key));}return rows;}
  /** 固定分页大小，缺失和截断都显式表示。 */
  private ObjectNode page(ArrayNode rows,ObjectNode args){int offset=args.path("offset").asInt();if(offset<0)fail("HARNESS_INVALID_TOOL","offset不能为负数");var out=mapper.createObjectNode().put("total",rows.size()).put("truncated",(long)offset+50<rows.size());var items=out.putArray("items");for(int n=offset;n<Math.min((long)offset+50,rows.size());n++)items.add(rows.get(n));if((long)offset+50<rows.size())out.put("nextOffset",offset+50);return out;}
  /** 行数限制与原诊断服务保持一致。 */
  private int lines(ObjectNode args){int preferred=2000;try{preferred=Integer.parseInt(preferences.get().at("/values/logLines").asText("2000"));}catch(NumberFormatException ignored){}int n=args.path("lines").asInt(preferred);if(n<1||n>10000)fail("HARNESS_INVALID_TOOL","日志行数应为1到10000");return n;}
  /** Session日志沿用管理员或运维角色，不能因开发角色可操作作业而放宽。 */
  private void requireSessionLogs(){var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();if(auth==null||auth.getAuthorities().stream().noneMatch(a->Set.of("ROLE_ADMIN","ROLE_OPERATOR").contains(a.getAuthority())))throw new BusinessException(HttpStatus.FORBIDDEN,"HARNESS_TOOL_DENIED","Session日志仅管理员或运维可读");}
  /** 固定端点读取前后核对资源UID和运行JobID，防止重建混入旧证据。 */
  private ObjectNode runtime(String name,ObjectNode args,String id){
    access.require(id,name.equals("flink_log_read")?"operate":"read");var job=jobs.get(id);var live=evidence.resource(job);
    String uid=live.at("/metadata/uid").asText(),jid=live.at("/status/jobStatus/jobId").asText();if(uid.isBlank())fail("HARNESS_RESOURCE_MISSING","当前运行资源不存在");
    String prefix="jobs/"+jid+"/",path;
    switch(name){
      case "flink_taskmanagers" -> path="taskmanagers";
      case "flink_log_read" -> {String tm=args.path("taskManagerId").asText();path=tm.isBlank()?"jobmanager/log":"taskmanagers/"+tm+"/log";}
      case "flink_exceptions_read" -> path=prefix+"exceptions?maxExceptions=20";
      case "flink_job_plan" -> path=prefix+"plan";
      case "flink_vertex_backpressure","flink_vertex_metrics" -> {String vertex=args.path("vertexId").asText();if(!vertex.matches("[a-f0-9]{32}"))fail("HARNESS_INVALID_TOOL","算子ID格式无效");path=prefix+"vertices/"+vertex+"/"+(name.equals("flink_vertex_backpressure")?"backpressure":"metrics");String metrics=args.path("metrics").asText();if(!metrics.isBlank()){if(!metrics.matches("[A-Za-z0-9.,_-]{1,2000}"))fail("HARNESS_INVALID_TOOL","指标ID格式无效");path+="?get="+metrics;}}
      case "checkpoint_configuration" -> path=prefix+"checkpoints/config";
      case "checkpoint_detail","checkpoint_operator_detail" -> {long cp=args.path("checkpointId").asLong();if(cp<0)fail("HARNESS_INVALID_TOOL","Checkpoint ID不能为负数");path=prefix+"checkpoints/details/"+cp;if(name.equals("checkpoint_operator_detail"))path+="/subtasks/"+args.path("vertexId").asText();}
      default -> path=prefix+"checkpoints";
    }
    if(path.startsWith("jobs/")&&!jid.matches("[a-f0-9]{32}"))fail("HARNESS_JOB_ID_UNAVAILABLE","尚无可核验的当前Flink Job ID");
    JsonNode value;
    try{value=flink.read(job.deploymentName(),path);}catch(IllegalArgumentException ex){fail("HARNESS_INVALID_TOOL","Flink目标参数格式无效");return null;}catch(IllegalStateException ex){fail("HARNESS_EVIDENCE_UNAVAILABLE","Flink端点不可达、版本不支持或响应超过预算；不能视为正常或零");return null;}
    var after=evidence.resource(jobs.get(id));if(!live.at("/metadata/uid").equals(after.at("/metadata/uid"))||!live.at("/status/jobStatus/jobId").equals(after.at("/status/jobStatus/jobId"))||!live.path("spec").equals(after.path("spec")))fail("HARNESS_CONTEXT_CHANGED","读取期间资源或运行身份变化，请重新取证");
    value=pageRuntime(value,args);value=diagnostics.redactEvidence(id,value);if(value==null)fail("HARNESS_REDACTION_UNAVAILABLE","证据脱敏不可用");
    var out=mapper.createObjectNode().put("resourceUid",uid).put("flinkJobId",jid).put("source",path);if(name.equals("flink_log_read")&&value.isObject())out.setAll((ObjectNode)value);else out.set("data",value);return out;
  }
  /** 大Checkpoint的算子/子任务及历史按需分页；每页标明采集一致性范围。 */
  private JsonNode pageRuntime(JsonNode value,ObjectNode args){
    if(!value.isObject())return value;var out=(ObjectNode)value.deepCopy();if(out.path("plan").isObject())out.set("plan",pageRuntime(out.path("plan"),args));int offset=args.path("offset").asInt();if(offset<0)fail("HARNESS_INVALID_TOOL","offset不能为负数");
    for(String key:List.of("tasks","subtasks","history","taskmanagers","nodes")){
      var rows=out.path(key);if(!rows.isArray()&&!rows.isObject())continue;int total=rows.size();
      JsonNode selected=rows.isArray()?mapper.createArrayNode():mapper.createObjectNode();int n=0;
      if(rows.isObject()){var fields=rows.fields();while(fields.hasNext()){var field=fields.next();if(n>=offset&&n<(long)offset+50)((ObjectNode)selected).set(field.getKey(),field.getValue());n++;}}
      else for(var row:rows){if(n>=offset&&n<(long)offset+50)((ArrayNode)selected).add(row);n++;}
      out.set(key,selected);var meta=out.withObject("/page").put("field",key).put("total",total).put("offset",offset).put("hasMore",(long)offset+50<total).put("consistency","本页重新采集；实时统计可能继续变化");if((long)offset+50<total)meta.put("nextOffset",offset+50);
    }return out;
  }
  /** 日志保存短期私有快照；分页读取同一内容，避免重新取尾部造成遗漏或重复。 */
  private ObjectNode logSnapshot(ObjectNode value,String job,String cluster,String session){
    var safe=(ObjectNode)guard.sanitize(protect(value));if(safe.path("text").asText().length()>4*1024*1024)fail("HARNESS_RESULT_TOO_LARGE","日志超过4MiB预算");
    for(var old:records.list("ai_harness_log_snapshots"))if(access.actor().equals(old.path("owner").asText())&&Instant.parse(old.path("expiresAt").asText()).isBefore(Instant.now()))records.delete("ai_harness_log_snapshots",old.path("id").asText(),old.path("revision").asLong());
    if(records.list("ai_harness_log_snapshots").stream().filter(r->access.actor().equals(r.path("owner").asText())).count()>=20)fail("HARNESS_LOG_LIMIT","短期日志快照达到20份，请稍后重试");
    safe.put("owner",access.actor()).put("jobId",job).put("clusterId",cluster).put("sessionId",session).put("expiresAt",Instant.now().plusSeconds(1800).toString());
    var row=records.save("ai_harness_log_snapshots",UUID.randomUUID().toString(),safe,0);return logPage(row,0);
  }
  /** 取回日志再次验证账号、领域读取权限和有效期。 */
  private ObjectNode storedLog(String name,ObjectNode args){var row=records.require("ai_harness_log_snapshots",args.path("logId").asText());if(!access.actor().equals(row.path("owner").asText()))throw new BusinessException(HttpStatus.FORBIDDEN,"FORBIDDEN","无权读取其他用户日志");if(Instant.parse(row.path("expiresAt").asText()).isBefore(Instant.now()))fail("HARNESS_LOG_EXPIRED","日志快照已过期，请重新读取");if(!row.path("jobId").asText().isBlank()){access.require(row.path("jobId").asText(),"operate");jobs.get(row.path("jobId").asText());}else{requireSessionLogs();sessions.observedResource(row.path("clusterId").asText());}
    if(name.equals("logs_release")){records.delete("ai_harness_log_snapshots",row.path("id").asText(),row.path("revision").asLong());return mapper.createObjectNode().put("released",true);}
    if(name.equals("logs_page"))return logPage(row,args.path("offset").asInt());
    String text=row.path("text").asText(),query=args.path("query").asText();var out=mapper.createObjectNode().put("logId",row.path("id").asText()).put("scope","仅本次已获取的日志快照").put("sourceTruncated",row.path("truncated").asBoolean());var matches=out.putArray("matches");int at=0;while((at=text.indexOf(query,at))>=0){if(matches.size()>=20){out.put("truncated",true);break;}matches.addObject().put("offset",at).put("text",text.substring(Math.max(0,at-250),Math.min(text.length(),at+query.length()+400)));at+=query.length();}return out;
  }
  /** 字符游标分页显式返回上游截断，不将快照尾部当完整历史。 */
  private ObjectNode logPage(ObjectNode row,int offset){String text=row.path("text").asText();if(offset<0||offset>text.length())fail("HARNESS_INVALID_TOOL","日志游标无效");int end=(int)Math.min(text.length(),(long)offset+12000);if(end<text.length()&&end>offset&&Character.isHighSurrogate(text.charAt(end-1)))end--;var out=row.deepCopy();out.remove(List.of("owner","text","id","revision"));out.put("logId",row.path("id").asText()).put("text",text.substring(offset,end)).put("offset",offset).put("hasMore",end<text.length()).put("scope","已读取尾部快照；不覆盖轮转或已删除的日志");if(end<text.length())out.put("nextOffset",end);return out;}
  /** 发现资源和参数化URI；不自动读取全部上下文。 */
  public ObjectNode resources(){var out=mapper.createObjectNode();out.putArray("resources").addObject().put("uri","flink://users/me/preferences").put("name","我的偏好").put("mimeType","application/json");var templates=out.putArray("resourceTemplates");for(String path:List.of("jobs/{id}/configuration","jobs/{id}/checkpoints","jobs/{id}/snapshots","jobs/{id}/pods","jobs/{id}/events","jobs/{id}/plan","sql/{id}/draft","sessions/{id}/configuration","catalogs/{id}/databases/{database}/tables/{table}","logs/{id}"))templates.addObject().put("uriTemplate","flink://"+path).put("name",path).put("mimeType","application/json");return out;}
  /** 资源URI只能映射到登记工具，不能作为URL访问。 */
  public ObjectNode resource(String uri,boolean allowLogs,String session){
    if(uri.equals("flink://users/me/preferences"))return preferences.get();
    if(uri.startsWith("flink://catalogs/")){
      var parts=uri.substring(17).split("/",-1);if(parts.length!=5||!parts[1].equals("databases")||!parts[3].equals("tables"))fail("HARNESS_RESOURCE_UNSUPPORTED","Catalog资源URI无效");
      var args=mapper.createObjectNode().put("catalogId",parts[0]).put("database",decode(parts[2])).put("table",decode(parts[4]));return call("catalog_metadata",args,"",allowLogs,session);
    }
    var match=java.util.regex.Pattern.compile("flink://(jobs|sql|logs|sessions)/([A-Za-z0-9_-]{1,200})(?:/(configuration|checkpoints|snapshots|draft|pods|events|plan))?").matcher(uri);if(!match.matches())fail("HARNESS_RESOURCE_UNSUPPORTED","未登记该资源URI");
    var args=mapper.createObjectNode();String name;
    if(match.group(1).equals("logs")){if(match.group(3)!=null)fail("HARNESS_RESOURCE_UNSUPPORTED","资源URI无效");name="logs_page";args.put("logId",match.group(2));}
    else if(match.group(1).equals("sessions")&&"configuration".equals(match.group(3))){name="session_config_read";args.put("sessionId",match.group(2));}
    else if(match.group(1).equals("sql")&&"draft".equals(match.group(3))){name="sql_draft_read";args.put("sqlId",match.group(2));}
    else if(match.group(1).equals("jobs")&&match.group(3)!=null){name=switch(match.group(3)){case "configuration"->"job_config_read";case "checkpoints"->"checkpoint_overview";case "snapshots"->"snapshot_list";case "pods"->"job_pods_read";case "events"->"job_events_read";case "plan"->"flink_job_plan";default->"";};args.put("jobId",match.group(2));}
    else{name="";}return call(name,args,"",allowLogs,session);
  }
  /** URI参数仅解码为服务参数，绝不拼接到网络地址。 */
  private String decode(String value){try{return java.net.URLDecoder.decode(value,java.nio.charset.StandardCharsets.UTF_8);}catch(IllegalArgumentException ex){fail("HARNESS_RESOURCE_UNSUPPORTED","URI编码无效");return "";}}
  /** 提示模板按名称稳定排序，版本随制品冻结。 */
  public ObjectNode prompts(){var out=mapper.createObjectNode();var list=out.putArray("prompts");PROMPTS.keySet().stream().sorted().forEach(name->list.addObject().put("name",name).put("description",PROMPTS.get(name)).put("version","0.0.10").putArray("arguments"));return out;}
  /** 返回结构化模板消息，不提升系统规则或授予执行权限。 */
  public ObjectNode prompt(String name){if(!PROMPTS.containsKey(name))fail("HARNESS_PROMPT_UNSUPPORTED","提示模板不存在");var out=mapper.createObjectNode().put("description",name).put("version","0.0.10");out.putArray("messages").addObject().put("role","user").putObject("content").put("type","text").put("text",PROMPTS.get(name));return out;}
  /** 统一机器错误码，拒绝不能安全执行的参数。 */
  private void fail(String code,String message){throw new BusinessException(HttpStatus.BAD_REQUEST,code,message);}
}
