package com.flinkweb.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import org.apache.calcite.avatica.util.Casing;
import org.apache.calcite.avatica.util.Quoting;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.flink.sql.parser.impl.FlinkSqlParserImpl;

/** 独立 JVM 中运行指定 Flink 版本的 SQL Parser，兼容 JDK 8/17 并避免 Calcite 依赖污染平台。 */
public final class ParserMain {
  /** 从标准输入读取有限大小的脚本数组，向标准输出返回单个 JSON 校验结果。 */
  public static void main(String[] args) throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Properties properties = new Properties();
    try (InputStream stream = ParserMain.class.getResourceAsStream("/validator.properties")) {
      properties.load(stream);
    }
    ObjectNode result =
        mapper.createObjectNode().put("version", properties.getProperty("flink.version"));
    int index = 0;
    boolean parsing = false;
    try {
      JsonNode input = mapper.readTree(readInput());
      JsonNode statements = input == null ? null : input.get("statements");
      if (statements == null
          || !statements.isArray()
          || statements.size() == 0
          || statements.size() > 100) throw new IllegalArgumentException("SQL 语句数组无效");
      SqlParser.Config config =
          SqlParser.config()
              .withParserFactory(FlinkSqlParserImpl.FACTORY)
              .withQuoting(Quoting.BACK_TICK)
              .withUnquotedCasing(Casing.UNCHANGED)
              .withQuotedCasing(Casing.UNCHANGED)
              .withIdentifierMaxLength(256);
      int bytes = 0;
      for (JsonNode statement : statements) {
        if (!statement.isTextual() || statement.asText().trim().isEmpty())
          throw new IllegalArgumentException("SQL 语句必须为非空字符串");
        bytes += statement.asText().getBytes(StandardCharsets.UTF_8).length;
        if (bytes > 32768) throw new IllegalArgumentException("SQL 超过 32 KiB");
      }
      parsing = true;
      for (JsonNode statement : statements) {
        index++;
        SqlParser.create(statement.asText(), config).parseStmt();
      }
      result.put("valid", true);
    } catch (SqlParseException ex) {
      result.put("valid", false).put("errorType", "SYNTAX").put("statement", index);
      if (ex.getPos() != null && ex.getPos().getLineNum() > 0 && ex.getPos().getColumnNum() > 0)
        result.put("line", ex.getPos().getLineNum()).put("column", ex.getPos().getColumnNum());
      result.put("message", "SQL 语法错误，请检查该位置附近的语法");
    } catch (Exception ex) {
      result
          .put("valid", false)
          .put("errorType", parsing ? "RUNTIME" : "INPUT")
          .put("message", parsing ? "SQL Parser 运行失败" : "SQL Parser 输入格式、语句数量或大小不符合要求");
    }
    System.out.write(mapper.writeValueAsBytes(result));
  }

  /** 使用 JDK 8 流 API 限制协议输入，不截断后继续解析，也不无限读入内存。 */
  private static byte[] readInput() throws java.io.IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int count;
    while ((count = System.in.read(buffer)) != -1) {
      if (output.size() + count > 131072) throw new java.io.IOException("SQL Parser 输入超限");
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }
}
