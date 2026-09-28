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
}
