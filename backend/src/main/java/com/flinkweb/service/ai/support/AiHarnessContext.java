package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;

/** 模型只接收目标摘要、历史索引及最近工具块，完整领域快照仍留在服务端按需读取。 */
public final class AiHarnessContext {
  private static final ObjectMapper JSON=new ObjectMapper();
  /** 不把SQL/YAML、全部镜像和诊断原文重复放入每轮首包。 */
  public static ObjectNode summary(ObjectNode context){
    var out=JSON.createObjectNode();for(String key:List.of("profile","jobName","flinkVersion","jobId","baseline","jobBaseline","runtimeStatus"))if(context.has(key))out.set(key,context.get(key).deepCopy());
    var draft=context.path("draft");if(draft.isObject()){var row=out.putObject("draft");for(String key:List.of("id","name","revision","jobId"))if(draft.has(key))row.set(key,draft.get(key));row.put("sqlCharacters",draft.path("sql").asText().length()).put("newDraft",draft.path("id").asText().isBlank());}
    var sections=out.putArray("availableSections");context.fieldNames().forEachRemaining(sections::add);
    out.put("detailPolicy","这是摘要，不是读取失败。按需用context_read(section)读取完整分区；更多工具用tools_search发现。");return out;
  }
  /** 用户输入只出现一次，较早历史提供显式预览与可回读的轮次标识。 */
  public static ObjectNode request(ObjectNode session,ObjectNode turn,List<ObjectNode> history){
    var out=JSON.createObjectNode().put("sessionId",session.path("id").asText()).put("latestUserInput",turn.path("content").asText());out.set("context",summary((ObjectNode)turn.path("context")));var target=out.putObject("target");for(String key:List.of("kind","jobId","sqlId"))if(session.has(key))target.set(key,session.get(key));
    String latest=turn.path("content").asText(),goal=turn.path("continuationGoal").asText(latest),initial=session.path("userGoal").asText(latest);
    if(!goal.equals(latest)&&!goal.equals(initial))out.put("goal",goal);if(!initial.equals(latest))out.put("initialUserGoal",initial);
    for(String key:List.of("preferences","taskTemplate","approvedRequirements","interactionResponse","preferenceRequest"))if(turn.has(key))out.set(key.equals("preferences")?"userPreferences":key,turn.get(key));
    if(session.has("memory"))out.set("privateMemory",preview(session.path("memory").toString(),2000));
    if(turn.at("/context/sharedSummary").isObject())out.set("sharedSummary",preview(turn.at("/context/sharedSummary").toString(),3000));
    var rows=out.putArray("conversationHistory");for(int n=0;n<history.size();n++){var old=history.get(n);var row=rows.addObject().put("turnId",old.path("id").asText()).put("sequence",old.path("sequence").asLong()).put("state",old.path("state").asText());String text=old.path("content").asText();if(!text.equals(initial))row.set("userMessage",preview(text,n>=history.size()-2?2000:320));else row.put("userMessageSource",initial.equals(latest)?"latestUserInput":"initialUserGoal");if(n>=history.size()-2)row.set("assistantReply",preview(old.path("reply").asText(),1500));}
    out.put("historyPolicy","历史预览标记了省略。需要精确原话或回复时，使用harness_turn_read(sessionId,turnId)，不要猜测被省略内容。");return out;
  }
  /** 截断只用于明确标记的摘要，不修改原消息、SQL或领域记录。 */
  private static ObjectNode preview(String text,int budget){int end=Math.min(text.length(),budget);if(end>0&&end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;return JSON.createObjectNode().put("text",text.substring(0,end)).put("truncated",end<text.length()).put("originalCharacters",text.length());}
  /** 整块裁剪已完成的assistant/tool配对，最近两块完整保留，较大时保留最近一块。 */
  public static int compact(ArrayNode messages,ObjectNode turn){
    var blocks=new ArrayList<ArrayNode>();var extra=JSON.createArrayNode();var receipts=JSON.createArrayNode();
    for(int n=2;n<messages.size();){var message=messages.get(n);if(message.path("role").asText().equals("assistant")&&!message.path("tool_calls").isEmpty()){var block=JSON.createArrayNode();block.add(message);n++;while(n<messages.size()&&messages.get(n).path("role").asText().equals("tool"))block.add(messages.get(n++));blocks.add(block);}else{boolean receipt=false;try{var data=JSON.readTree(message.path("content").asText());if(message.path("_harnessContext").asBoolean()&&message.path("role").asText().equals("user")&&data!=null&&data.path("harnessContextReceipts").isArray()){receipts.addAll((ArrayNode)data.path("harnessContextReceipts"));receipt=true;}}catch(Exception ignored){}if(!receipt)extra.add(message);n++;}}
    int keep=Math.min(2,blocks.size());if(keep==2&&blocks.get(blocks.size()-1).toString().length()+blocks.get(blocks.size()-2).toString().length()>50000)keep=1;int removed=blocks.size()-keep;if(removed<=0)return 0;
    for(int n=0;n<removed;n++){var block=blocks.get(n);var calls=block.get(0).path("tool_calls");for(int k=0;k<calls.size();k++){var call=calls.get(k);var row=receipts.addObject().put("tool",call.at("/function/name").asText()).put("resultBodyOmitted",true);if(k+1<block.size())try{var result=JSON.readTree(block.get(k+1).path("content").asText());for(String field:List.of("status","error","planId","logId","nextOffset","resourceUid","flinkJobId","queriedAt"))if(result.has(field))row.set(field,result.get(field));}catch(Exception ignored){}}}
    while(receipts.size()>24)receipts.remove(0);var state=JSON.createObjectNode();state.set("harnessContextReceipts",receipts);for(String key:List.of("plan","scene","planId","toolCalls"))if(turn.has(key))state.set(key,turn.get(key));if(turn.has("memoryCandidate"))state.set("memory",preview(turn.path("memoryCandidate").toString(),2000));state.put("notice","旧工具正文已省略；当前候选/证据可context_read(candidate/evidence)，精确历史可harness_turn_read；回执不是新授权或实时状态。");
    var next=JSON.createArrayNode();next.add(messages.get(0));next.add(messages.get(1));next.addObject().put("role","user").put("_harnessContext",true).put("content",state.toString());next.addAll(extra);for(int n=removed;n<blocks.size();n++)next.addAll(blocks.get(n));messages.removeAll();messages.addAll(next);return removed;
  }
}
