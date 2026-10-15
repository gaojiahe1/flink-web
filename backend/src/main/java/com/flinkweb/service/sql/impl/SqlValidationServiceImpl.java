package com.flinkweb.service.sql.impl;

import com.flinkweb.dto.sql.SqlCheckResult;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.sql.SqlValidationService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 检查 SQL 脚本边界、引号、语句顺序及基础支持范围；不连接外部 Catalog 或运行 Flink Planner。 */
@Service
public class SqlValidationServiceImpl implements SqlValidationService {
  private static final Pattern DDL =
      Pattern.compile("(?is)^CREATE\\s+(TEMPORARY\\s+)?(TABLE|VIEW|CATALOG|DATABASE)\\b.*");
  private static final Pattern INSERT = Pattern.compile("(?is)^INSERT\\s+(INTO|OVERWRITE)\\b.*");

  /** 拆分脚本并限制为先建表/视图、后 INSERT 的单 Application 执行序列。 */
  public SqlCheckResult validate(String script) {
    return validateScript(script, false);
  }

  /** 只读查询可交给验证 Runner，不能借此发布没有 Sink 的 Application。 */
  public SqlCheckResult validateVerification(String script) {
    return validateScript(script, true);
  }

  /** 共用引号/注释切分、大小和顺序限制，查询只在验证入口开放。 */
  private SqlCheckResult validateScript(String script, boolean queries) {
    if (script == null || script.isBlank()) fail("请输入 SQL 脚本");
    if (script.getBytes(StandardCharsets.UTF_8).length > 32768) fail("基础版 SQL 脚本不能超过 32 KiB");
    List<String> statements = split(script);
    int inserts = 0;
    int reads = 0;
    for (String statement : statements) {
      if (INSERT.matcher(statement).matches()) inserts++;
      else if (queries && statement.matches("(?is)^(SELECT|WITH|VALUES)\\b.*")) reads++;
      else if (DDL.matcher(statement).matches() || statement.matches("(?is)^USE\\s+.*")) {
        if (inserts + reads > 0) fail("CREATE / USE 必须放在所有 INSERT / SELECT 之前");
      } else
        fail(
            "支持 CREATE TABLE/VIEW/CATALOG/DATABASE、USE 和 INSERT INTO/OVERWRITE；SET 请填写到运行配置，暂不支持"
                + " SELECT 结果预览或 SQL Client 命令");
    }
    if (inserts + reads == 0) fail(queries ? "至少需要一条 INSERT 或 SELECT 查询" : "至少需要一条 INSERT 写入语句");
    if (statements.size() > 100) fail("基础版最多支持 100 条语句");
    return new SqlCheckResult(
        true,
        "BASIC",
        statements.size(),
        inserts,
        "基础检查通过；尚未校验表字段、连接器和 Catalog，最终语义由 Flink 运行时校验。",
        List.copyOf(statements));
  }

  /** 按引号、反引号及注释状态识别分号，避免把字符串或注释中的分号当成语句结束。 */
  private List<String> split(String script) {
    List<String> result = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    char quote = 0;
    boolean line = false, block = false;
    for (int i = 0; i < script.length(); i++) {
      char c = script.charAt(i), next = i + 1 < script.length() ? script.charAt(i + 1) : 0;
      if (line) {
        if (c == '\n' || c == '\r') {
          line = false;
          current.append(' ');
        }
        continue;
      }
      if (block) {
        if (c == '*' && next == '/') {
          block = false;
          i++;
          current.append(' ');
        }
        continue;
      }
      if (quote != 0) {
        current.append(c);
        if (c == quote) {
          if (next == quote) {
            current.append(next);
            i++;
          } else quote = 0;
        }
        continue;
      }
      if (c == '-' && next == '-') {
        line = true;
        i++;
        current.append(' ');
        continue;
      }
      if (c == '/' && next == '*') {
        block = true;
        i++;
        current.append(' ');
        continue;
      }
      if (c == '\'' || c == '"' || c == 96) {
        quote = c;
        current.append(c);
        continue;
      }
      if (c == ';') {
        add(result, current);
        continue;
      }
      current.append(c);
    }
    if (quote != 0) fail("SQL 中存在未闭合的引号或反引号");
    if (block) fail("SQL 中存在未闭合的块注释");
    add(result, current);
    return result;
  }

  /** 忽略空语句，将完整语句收集到检查结果中。 */
  private void add(List<String> result, StringBuilder current) {
    String value = current.toString().strip();
    if (!value.isEmpty()) result.add(value);
    current.setLength(0);
  }

  /** 输出不包含脚本正文的基础校验错误。 */
  private void fail(String message) {
    throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_SQL", message);
  }
}
