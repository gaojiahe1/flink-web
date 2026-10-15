package com.flinkweb.service.ai.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.flinkweb.exception.common.BusinessException;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** 需求向导的显式数据角色与场景契约，不以模型判断代替输入约束。 */
public final class AiWizardContract {
  /** 仅提供契约校验方法，不创建实例。 */
  private AiWizardContract() {}

  /** 新版向导严格校验，旧任务协议继续沿用原有规则。 */
  public static Map<String, String> validate(JsonNode spec, JsonNode definitions) {
    if (!spec.has("workflowVersion")) return Map.of();
    if (!spec.path("workflowVersion").isIntegralNumber() || !spec.path("workflowVersion").canConvertToInt()
        || spec.path("workflowVersion").asInt() != 2) reject("不支持的需求向导版本");
    String pattern = spec.path("pattern").asText();
    if (!Set.of("transform", "lookup", "window").contains(pattern)) reject("请选择清洗转换、维表打宽或窗口统计场景");
    if (!Set.of("Asia/Shanghai", "UTC").contains(spec.path("timezone").asText())) reject("请选择有效时区");
    Map<String, JsonNode> tables = new HashMap<>();
    for (var table : definitions) {
      String name = table.path("tableName").asText().toLowerCase(Locale.ROOT);
      if (tables.putIfAbsent(name, table) != null) reject("表名重复，请核对已选数据与本地定义");
    }
    var bindings = spec.path("bindings");
    if (!bindings.isArray() || bindings.size() != tables.size()) reject("每张表都需要明确输入、维表或输出角色");
    Map<String, String> roles = new HashMap<>();
    for (var binding : bindings) {
      if (!binding.isObject() || !binding.path("tableName").isTextual()) reject("数据角色结构无效");
      String name = binding.path("tableName").asText().toLowerCase(Locale.ROOT);
      String role = binding.path("role").asText();
      if (!tables.containsKey(name) || !Set.of("source", "lookup", "sink").contains(role)
          || roles.putIfAbsent(name, role) != null) reject("数据角色与所选表不一致");
      String connector = tables.get(name).path("connectorType").asText();
      if (role.equals("source") && connector.equals("print")) reject("Print 只能作为输出表");
      if (role.equals("sink") && connector.equals("datagen")) reject("DataGen 只能作为输入表");
      if (role.equals("lookup") && !Set.of("mysql", "postgresql").contains(connector)) reject("首批维表仅支持 JDBC 数据源");
    }
    if (!roles.containsValue("source") || !roles.containsValue("sink")) reject("至少选择一张输入表和一张输出表");
    if (pattern.equals("lookup") && !roles.containsValue("lookup")) reject("维表打宽场景需要选择维表");
    if (pattern.equals("window")) {
      if (!spec.path("windowMinutes").isIntegralNumber() || !spec.path("windowMinutes").canConvertToInt()
          || spec.path("windowMinutes").asInt() < 1 || spec.path("windowMinutes").asInt() > 1440)
        reject("窗口应为1–1440分钟的整数");
      String time = spec.path("eventTime").asText();
      String eventTable = spec.path("eventTable").asText().toLowerCase(Locale.ROOT);
      boolean matched = tables.entrySet().stream().anyMatch(entry ->
          entry.getKey().equals(eventTable) && roles.get(entry.getKey()).equals("source") && !time.isBlank()
              && time.equals(entry.getValue().path("watermarkColumn").asText()));
      if (!matched) reject("窗口统计必须选择输入表中已定义水位线的事件时间字段");
      if (!spec.path("outputMode").asText().equals("window-close")) reject("首批窗口统计使用窗口结束输出");
    } else if (spec.has("windowMinutes") || spec.has("eventTime") || spec.has("eventTable")) reject("非窗口场景不接受窗口或事件时间参数");
    return Map.copyOf(roles);
  }

  /** 在确定性语句检查之后再次限制写入范围，不能将输入表误作输出表。 */
  public static void validateTargets(JsonNode spec, List<String> statements) {
    if (!spec.has("workflowVersion")) return;
    Set<String> sinks = new HashSet<>();
    for (var binding : spec.path("bindings"))
      if (binding.path("role").asText().equals("sink"))
        sinks.add(binding.path("tableName").asText().toLowerCase(Locale.ROOT));
    var insert = Pattern.compile("(?is)^INSERT\\s+INTO\\s+([^\\s(]+)");
    for (String statement : statements) {
      var match = insert.matcher(statement);
      if (match.find() && !sinks.contains(match.group(1).replace("`", "").toLowerCase(Locale.ROOT)))
        reject("SQL 建议写入了需求确认单以外的输出表，未应用任何修改");
    }
  }

  /** 返回稳定错误码，供界面引导用户补全契约。 */
  private static void reject(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "AI_SPEC_REQUIRED", message);
  }
}
