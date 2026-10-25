package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;

import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.service.sql.SqlValidationService;
import com.flinkweb.service.sql.impl.SqlValidationServiceImpl;
import org.junit.jupiter.api.Test;

/** 验证 SQL 基础检查不会误拆分引号、注释和多写入脚本。 */
class SqlValidationServiceTest {
  private final SqlValidationService validation = new SqlValidationServiceImpl();

  /** 字符串中的分号和双写单引号保留，注释不生成额外语句。 */
  @Test
  void keepsQuotedSemicolonsAndComments() {
    var result =
        validation.validate(
            "-- ; ignored\n"
                + "CREATE TABLE t (a STRING) WITH ('connector'='print'); /* ; */ INSERT"
                + " INTO t VALUES ('a;''b');");
    assertThat(result.level()).isEqualTo("BASIC");
    assertThat(result.statementCount()).isEqualTo(2);
    assertThat(result.statements().get(1)).contains("'a;''b'");
  }

  /** 多个写入允许合并，但 DDL 不能插入到写入语句之间。 */
  @Test
  void acceptsMultipleInsertsButRejectsInterleavedDdl() {
    assertThat(
            validation.validate("INSERT INTO a VALUES(1); INSERT INTO b VALUES(2)").insertCount())
        .isEqualTo(2);
    assertThatThrownBy(() -> validation.validate("INSERT INTO a VALUES(1); CREATE TABLE b (n INT)"))
        .isInstanceOf(BusinessException.class);
  }

  /** 未闭合引号、块注释及无写入脚本会被明确拒绝。 */
  @Test
  void rejectsBrokenOrIncompleteScripts() {
    for (String input :
        java.util.List.of(
            "INSERT INTO t VALUES ('bad);",
            "/* comment",
            "CREATE TABLE t (a INT);",
            "",
            "SELECT 1;",
            "SET 'execution.target'='local'; INSERT INTO t VALUES (1);"))
      assertThatThrownBy(() -> validation.validate(input)).isInstanceOf(BusinessException.class);
  }

  /** 按 UTF-8 字节数限制输入，避免编码后参数超出平台约定。 */
  @Test
  void limitsEncodedScriptSize() {
    assertThatThrownBy(
            () -> validation.validate("INSERT INTO t VALUES ('" + "中".repeat(12000) + "')"))
        .isInstanceOf(BusinessException.class);
  }

  /** 验证提前拦截 CTAS，查询及包含相似字符串的 DDL 正常，Application 保留原范围。 */
  @Test
  void verificationRejectsCtasBeforeRunnerAndExplainsQueryScope() {
    for (String query :
        java.util.List.of("SELECT 1", "WITH t AS (SELECT 1) SELECT * FROM t", "VALUES (1)"))
      assertThat(validation.validateVerification(query).valid()).isTrue();
    for (String query :
        java.util.List.of(
            "SELECT 1", "(SELECT 1)", "WITH t AS (SELECT 1) SELECT * FROM t", "VALUES (1)"))
      assertThatThrownBy(
              () -> validation.validateVerification("CREATE TEMPORARY TABLE t AS " + query))
          .hasMessageContaining("验证不执行 CREATE TABLE AS");
    assertThat(
            validation
                .validateVerification(
                    "CREATE TABLE t(a STRING) WITH ('comment'='AS SELECT'); SELECT 1")
                .valid())
        .isTrue();
    assertThat(
            validation
                .validate("CREATE TABLE t AS SELECT 1; INSERT INTO sink SELECT * FROM t")
                .valid())
        .isTrue();
    assertThatThrownBy(() -> validation.validate("SELECT 1")).hasMessageContaining("请使用 SQL 验证");
    assertThatThrownBy(() -> validation.validateVerification("SET 'a'='b'; SELECT 1"))
        .hasMessageContaining("SET / RESET")
        .hasMessageNotContaining("暂不支持 SELECT");
  }

  /** SET 与 DDL 按顺序保留，提交目标和依赖参数不可越过部署约束。 */
  @Test
  void settingsDropAndFunctionsAreSupportedWithoutClientCommands() {
    var checked =
        validation.validate(
            "SET 'table.local-time-zone'='Asia/Shanghai'; RESET 'parallelism.default'; DROP"
                + " TEMPORARY SYSTEM FUNCTION IF EXISTS normalize; CREATE TEMPORARY SYSTEM FUNCTION"
                + " normalize AS 'example.Normalize'; DROP TABLE IF EXISTS old_t; ALTER TABLE t"
                + " RENAME TO t2; INSERT INTO sink SELECT normalize(v) FROM t2");
    assertThat(checked.statementCount()).isEqualTo(7);
    assertThat(checked.statements().get(0)).contains("table.local-time-zone");
    for (String key :
        java.util.List.of(
            "execution.target", "pipeline.jars", "kubernetes.namespace", "tableXexecXfoo"))
      assertThatThrownBy(
              () -> validation.validate("SET '" + key + "'='value'; INSERT INTO sink VALUES(1)"))
          .hasMessageContaining("SET / RESET");
    assertThatThrownBy(() -> validation.validate("INSERT INTO sink VALUES(1); DROP TABLE old"))
        .hasMessageContaining("之前");
    assertThatThrownBy(
            () -> validation.validate("ADD JAR 'file:///tmp/a.jar'; INSERT INTO sink VALUES(1)"))
        .hasMessageContaining("UDF");
  }
}
