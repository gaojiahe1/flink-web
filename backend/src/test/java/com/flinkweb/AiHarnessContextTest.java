package com.flinkweb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.flinkweb.service.ai.support.AiHarnessContext;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 摘要首包与工具配对裁剪回归，避免重复上下文或丢失可回读的原始数据。 */
class AiHarnessContextTest {
  private final ObjectMapper mapper=new ObjectMapper();
  /** YAML/SQL与旧回复不自动重发，当前用户输入唯一，保留历史原文读取标识。 */
  @Test
  void sendsBriefSnapshotAndHistoryReferences(){
    var context=mapper.createObjectNode().put("profile","sql");context.putObject("draft").put("id","sql-1").put("name","订单").put("sql","SELECT "+"x".repeat(30000));context.putObject("current").put("yaml","yaml-".repeat(20000));context.putObject("baseline").put("sqlRevision",2);
    var turn=mapper.createObjectNode().put("content","唯一用户请求").put("continuationGoal","唯一用户请求");turn.set("context",context);
    var session=mapper.createObjectNode().put("id","session").put("kind","sql").put("sqlId","sql-1").put("userGoal","唯一用户请求");var history=new ArrayList<ObjectNode>();for(int n=0;n<8;n++)history.add(mapper.createObjectNode().put("id","turn-"+n).put("sequence",n).put("content",n==0?"唯一用户请求":"历史约束"+"中文😀".repeat(3000)).put("reply","旧回复".repeat(5000)));
    var request=AiHarnessContext.request(session,turn,history);String text=request.toString();assertThat(text).doesNotContain("yaml-yaml-","SELECT xxx","userRequirementsHistory");assertThat(text.indexOf("唯一用户请求")).isEqualTo(text.lastIndexOf("唯一用户请求"));assertThat(text.length()).isLessThan(20000);assertThat(request.at("/conversationHistory/1/turnId").asText()).isEqualTo("turn-1");assertThat(request.at("/conversationHistory/1/userMessage/truncated").asBoolean()).isTrue();assertThat(context.at("/draft/sql").asText()).hasSize(30007);
  }
  /** 更换本轮目标仍保留初始目标，续接子任务单独表达且不重复正文。 */
  @Test
  void preservesInitialAndContinuationGoals(){
    var session=mapper.createObjectNode().put("id","s").put("userGoal","最初创建作业");var turn=mapper.createObjectNode().put("content","补充：选择Checkpoint").put("continuationGoal","现在排查故障");turn.putObject("context").put("profile","diagnosis");var request=AiHarnessContext.request(session,turn,List.of());assertThat(request.path("initialUserGoal").asText()).isEqualTo("最初创建作业");assertThat(request.path("goal").asText()).isEqualTo("现在排查故障");
  }
  /** 旧大结果只保留回执，最新块保持完整assistant/tool对应关系。 */
  @Test
  void compactsCompleteToolPairsAndPreservesLatestResult()throws Exception{
    var messages=mapper.createArrayNode();messages.addObject().put("role","system").put("content","system");messages.addObject().put("role","user").put("content","目标");for(int n=0;n<5;n++)append(messages,n,"body-"+n+"-"+"x".repeat(30000));
    var state=mapper.createObjectNode().put("scene","diagnosis");state.putArray("plan").add("读取日志");int before=messages.toString().length();assertThat(AiHarnessContext.compact(messages,state)).isEqualTo(4);assertThat(messages.toString().length()).isLessThan(before/2);assertThat(messages.toString()).doesNotContain("body-0-","body-1-").contains("body-4-");
    assertThat(messages.get(messages.size()-1).path("tool_call_id").asText()).isEqualTo("call-4");assertThat(messages.get(messages.size()-2).at("/tool_calls/0/id").asText()).isEqualTo("call-4");assertThat(mapper.readTree(messages.get(2).path("content").asText()).path("harnessContextReceipts")).hasSize(4);
    append(messages,5,"latest");AiHarnessContext.compact(messages,state);assertThat(messages.toString()).contains("latest");
  }
  /** 测试构造真实配对协议块，不把工具结果伪装成用户消息。 */
  private void append(ArrayNode messages,int n,String body){
    var call=messages.addObject().put("role","assistant").put("content","").putArray("tool_calls").addObject().put("id","call-"+n).put("type","function");call.putObject("function").put("name","logs_read").put("arguments","{}");messages.addObject().put("role","tool").put("tool_call_id","call-"+n).put("content",mapper.createObjectNode().put("logId","log-"+n).put("text",body).toString());
  }
}
