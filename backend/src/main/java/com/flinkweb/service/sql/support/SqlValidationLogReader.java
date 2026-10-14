package com.flinkweb.service.sql.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.flinkweb.client.flink.SessionRestClient;
import com.flinkweb.config.sql.SqlValidationLog;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 固定日志 I/O 与解析；各成功文件独立提交，五秒总预算不占用验证状态锁。 */
@Component
public class SqlValidationLogReader {
  private final SessionRestClient rest;
  private final ObjectMapper mapper;

  /** 注入受限 REST 和 JSON 解析器。 */
  public SqlValidationLogReader(SessionRestClient rest, ObjectMapper mapper) {
    this.rest = rest;
    this.mapper = mapper;
  }

  /** 单条完整日志事件，顺序号属于对应 TM，不以业务值去重。 */
  public record Event(long sequence, int sink, String kind, String value) {}

  /** 在共享预算内逐文件读取；修订或取消意图变化后停止追加迟到结果。 */
  public void read(
      ObjectNode run,
      ToLongFunction<String> cursor,
      BiConsumer<String, List<Event>> commit,
      BooleanSupplier active) {
    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    String deployment = run.path("deploymentName").asText(), id = run.path("id").asText();
    Pattern records = Pattern.compile("(?m)^FWV_EVENT\\|(\\d+)\\|([^\\r\\n]*)\\r?\\n");
    Pattern rows =
        Pattern.compile(
            "^FWV_" + id + "_(\\d+)(?::\\d+)?>\\s*(\\+I|-U|\\+U|-D)\\[(.*)]$", Pattern.DOTALL);
    for (var tm :
        rest.budgetedCall(deployment, "GET", "/taskmanagers", null, remaining(deadline))
            .path("taskmanagers")) {
      if (!active.getAsBoolean()) return;
      String key = tm.path("id").asText();
      Set<String> available = new HashSet<>();
      for (var file :
          rest.budgetedCall(
                  deployment, "GET", "/taskmanagers/" + key + "/logs", null, remaining(deadline))
              .path("logs")) available.add(file.path("name").asText());
      for (String filename : SqlValidationLog.FILES) {
        if (!active.getAsBoolean()) return;
        if (!available.contains(filename)) continue;
        var matcher =
            records.matcher(
                rest.budgetedValidationLog(deployment, key, filename, remaining(deadline)));
        List<Event> page = new ArrayList<>();
        long previous = cursor.applyAsLong(key);
        while (matcher.find()) {
          remaining(deadline);
          long sequence = Long.parseLong(matcher.group(1));
          if (sequence <= previous) continue;
          String message;
          try {
            message = mapper.readTree("\"" + matcher.group(2) + "\"").asText();
          } catch (java.io.IOException ex) {
            continue;
          }
          var row = rows.matcher(message);
          if (!row.matches()) continue;
          page.add(
              new Event(sequence, Integer.parseInt(row.group(1)) + 1, row.group(2), row.group(3)));
          previous = sequence;
          if (page.size() >= SqlValidationLog.ROW_LIMIT) break;
        }
        commit.accept(key, page);
      }
    }
  }

  /** 使用单调时钟限制多文件总体耗时，不按每个文件重新计时。 */
  private Duration remaining(long deadline) {
    long nanos = deadline - System.nanoTime();
    if (nanos <= 0) throw new IllegalStateException("验证日志采集时间预算已耗尽");
    return Duration.ofNanos(nanos);
  }
}
