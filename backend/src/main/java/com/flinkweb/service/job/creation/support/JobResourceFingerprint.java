package com.flinkweb.service.job.creation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.flinkweb.service.sql.support.SqlDeploymentSource;
import java.util.ArrayList;
import java.util.Collections;

/** 以字段顺序无关、数值等值的配置摘要核验运行资源，状态变化不改变配置身份。 */
public final class JobResourceFingerprint {
  /** 工具类不保存资源、凭据或跨请求缓存。 */
  private JobResourceFingerprint() {}
  /** 相同配置即使 JSON 字段重排或整数表示不同，也具有相同摘要。 */
  public static String hash(JsonNode node) {return SqlDeploymentSource.hash(canonical(node));}
  /** 对对象键排序、保留数组顺序并规范数值，文本仍按 JSON 转义。 */
  private static String canonical(JsonNode node) {
    if(node.isNumber())return node.decimalValue().stripTrailingZeros().toPlainString();
    if(node.isObject()) {
      var keys=new ArrayList<String>();node.fieldNames().forEachRemaining(keys::add);Collections.sort(keys);
      var values=new ArrayList<String>();
      for(String key:keys)values.add(TextNode.valueOf(key)+":"+canonical(node.get(key)));
      return "{"+String.join(",",values)+"}";
    }
    if(node.isArray()) {var values=new ArrayList<String>();for(var value:node)values.add(canonical(value));return "["+String.join(",",values)+"]";}
    return node.toString();
  }
}
