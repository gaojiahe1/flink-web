package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.util.*;

/** 单轮进展检测，不限制有效工具链长度；相同失败和无实质进展进入恢复或停止。 */
public final class AiHarnessProgress {
  private final ObjectMapper mapper;
  private final Deque<String> recent=new ArrayDeque<>();
  private final Set<String> meaningful=new LinkedHashSet<>();
  private int failures,idle;

  /** 状态仅属于本次执行，不导入另一轮的失败或批准。 */
  public AiHarnessProgress(ObjectMapper mapper){this.mapper=mapper;}

  /** 规范化参数和证据中的易变字段，避免键顺序或时间戳掩盖同一循环。 */
  private JsonNode stable(JsonNode value){
    if(value.isObject()){
      var out=mapper.createObjectNode();var names=new TreeSet<String>();value.fieldNames().forEachRemaining(names::add);
      for(String name:names)if(!Set.of("at","observedAt","queriedAt","collectedAt","createdAt","updatedAt","planId","snapshotId").contains(name))out.set(name,stable(value.get(name)));
      return out;
    }
    if(value.isArray()){var out=mapper.createArrayNode();value.forEach(item->out.add(stable(item)));return out;}
    return value;
  }

  /** 不以调用成功代替推进：记忆改写不清除失败，同类错误不能靠微调行号绕过。 */
  public ObjectNode record(String tool,ObjectNode args,ObjectNode output){
    boolean blocked=output.at("/run/available").isBoolean()&&!output.at("/run/available").asBoolean();
    boolean failed=blocked||output.has("error")||output.at("/check/valid").isBoolean()&&!output.at("/check/valid").asBoolean();
    String message=blocked?output.at("/run/message").asText():output.path("message").asText(output.at("/check/message").asText());
    String family=failed?tool+":"+output.path("error").asText("SQL_CHECK_FAILED")+":"+message.replaceAll("\\d+","#"):
        tool+":"+SqlDeploymentSource.hash(stable(args).toString()+stable(output).toString());
    recent.addLast(family);while(recent.size()>12)recent.removeFirst();
    long repeats=recent.stream().filter(family::equals).count();
    String progressKey=tool+":"+SqlDeploymentSource.hash(stable(args).toString()+stable(output).toString());
    boolean progress=!failed&&!Set.of("memory_update","plan_update").contains(tool)&&meaningful.add(progressKey);
    if(meaningful.size()>128)meaningful.remove(meaningful.iterator().next());
    if(progress){idle=0;failures=0;}else{idle++;if(failed)failures++;}
    var result=mapper.createObjectNode().put("repeats",repeats).put("consecutiveFailures",failures).put("stepsWithoutProgress",idle);
    if(repeats>=3||failures>=6||idle>=12){
      result.put("action","stop").put("message","检测到重复失败或持续无进展，已停止本轮循环。"+(message.isBlank()?"":" 最近问题："+message));
      result.putArray("nextSteps").add("核对下方错误与定位片段，修改具体方案后再继续").add("运行问题先采集配置、Pod/事件等证据，避免重复生成同一 SQL").add("必要时切换工具调用能力更强的模型；已完成结果保留，未自动提交变更");
    }else if(repeats==2||failures==3||idle==6){
      result.put("action","recover").put("message","当前策略重复或没有实质进展。请根据错误定位更换具体方案；不要原样重试，也不要重复询问已有答案。运行问题使用证据与 repair_propose，不要只重复 sql_propose。下一次同类重复失败将停止本轮。");
    }else result.put("action","continue");
    return result;
  }
}
