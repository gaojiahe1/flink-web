package com.flinkweb.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.dto.sql.SqlCheckResult;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.sql.impl.SqlParserServiceImpl;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 验证 Parser 版本降级，以及实际独立进程的语法检查协议。 */
@ExtendWith(OutputCaptureExtension.class)
class SqlParserServiceTest {
  @TempDir Path directory;
  final ObjectMapper mapper = new ObjectMapper();

  /** 替换进程边界，真实执行协议解析、错误分类和日志输出。 */
  private SqlParserServiceImpl protocol(String output, int exit) throws Exception {
    Files.createDirectories(directory.resolve("1.20.3"));
    Files.write(directory.resolve("1.20.3/validator.jar"), new byte[0]);
    var records = mock(RecordRepository.class);
    when(records.require("images", "image"))
        .thenReturn(mapper.createObjectNode().put("flinkVersion", "1.20.3"));
    var process = mock(Process.class);
    when(process.getOutputStream()).thenReturn(new java.io.ByteArrayOutputStream());
    when(process.getInputStream())
        .thenReturn(
            new java.io.ByteArrayInputStream(
                output.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    when(process.waitFor(anyLong(), any())).thenReturn(true);
    when(process.exitValue()).thenReturn(exit);
    return new SqlParserServiceImpl(records, mapper, directory.toString()) {
      /** 仅替换操作系统进程，业务错误处理仍走生产代码。 */
      @Override
      protected Process startParser(ProcessBuilder builder) {
        return process;
      }
    };
  }

  /** 输入异常不冒充语法错误；旧协议无位置时不输出零行列或 SQL 内容。 */
  @Test
  void protocolDistinguishesInputAndSyntaxWithoutInventingLocations() throws Exception {
    var input = protocol("{\"version\":\"1.20.3\",\"valid\":false,\"errorType\":\"INPUT\"}", 0);
    assertThatThrownBy(() -> input.check(sample("private-sql-text"), "image"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("SQL_PARSER_INPUT_ERROR"))
        .hasMessageNotContaining("第 0")
        .hasMessageNotContaining("private-sql-text");
    var syntax =
        protocol(
            "{\"version\":\"1.20.3\",\"valid\":false,\"statement\":1,\"message\":\"private-sql-text\"}",
            0);
    assertThatThrownBy(() -> syntax.check(sample("private-sql-text"), "image"))
        .hasMessageContaining("第 1 条语句")
        .hasMessageNotContaining("第 0")
        .hasMessageNotContaining("private-sql-text");
  }

  /** 空输出和退出码分类可排查，日志不包含 SQL、上游输出或凭据。 */
  @Test
  void emptyOutputAndAbnormalExitHaveSafeDiagnosticLogs(CapturedOutput captured) throws Exception {
    var empty = protocol("", 0);
    assertThatThrownBy(() -> empty.check(sample("private-sql-text"), "image"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("SQL_VALIDATOR_PROTOCOL"));
    var failed = protocol("private-output password=private-value", 17);
    assertThatThrownBy(() -> failed.check(sample("private-sql-text"), "image"))
        .isInstanceOfSatisfying(
            BusinessException.class, ex -> assertThat(ex.code()).isEqualTo("SQL_VALIDATOR_EXIT"));
    assertThat(captured.getAll())
        .contains("stage=OUTPUT", "stage=EXIT", "exit=17")
        .doesNotContain("private-output", "private-value", "private-sql-text");
  }

  /** 安装目录不可读时保留空列表 API，同时输出脱敏故障类型。 */
  @Test
  void unreadableInstallationDirectoryIsLoggedWithoutPathOrExceptionMessage(CapturedOutput captured)
      throws Exception {
    var parser =
        new SqlParserServiceImpl(mock(RecordRepository.class), mapper, directory.toString());
    try (var files = mockStatic(Files.class)) {
      files
          .when(
              () ->
                  Files.readAttributes(
                      directory.toAbsolutePath().normalize(),
                      java.nio.file.attribute.BasicFileAttributes.class))
          .thenThrow(new AccessDeniedException("private-directory"));
      assertThat(parser.installed()).isEmpty();
    }
    assertThat(captured.getAll())
        .contains("AccessDeniedException")
        .doesNotContain("private-directory");
  }

  /** 无精确版本适配器时必须明确返回 BASIC。 */
  @Test
  void unsupportedVersionIsNeverReportedAsParserValidated() {
    var records = mock(RecordRepository.class);
    when(records.require("images", "image"))
        .thenReturn(mapper.createObjectNode().put("flinkVersion", "2.3.0"));
    var parser = new SqlParserServiceImpl(records, mapper, directory.toString());
    var checked = parser.check(sample("INSERT INTO sink VALUES (1)"), "image");
    assertThat(checked.level()).isEqualTo("BASIC");
    assertThat(checked.message()).contains("未安装 Flink 2.3.0");
  }

  /** 在显式提供编译产物时运行真实 1.20.3 Parser，不用模拟器冒充语法校验。 */
  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "flink.test.validator",
      matches = ".+")
  void isolatedParserValidatesActualSyntax() throws Exception {
    Path jar = Path.of(System.getProperty("flink.test.validator"));
    Files.createDirectories(directory.resolve("1.20.3"));
    Files.copy(jar, directory.resolve("1.20.3/validator.jar"));
    var records = mock(RecordRepository.class);
    when(records.require("images", "image"))
        .thenReturn(mapper.createObjectNode().put("flinkVersion", "1.20.3"));
    var parser = new SqlParserServiceImpl(records, mapper, directory.toString());
    assertThat(parser.check(sample("INSERT INTO sink VALUES (1)"), "image").level())
        .isEqualTo("PARSER");
    assertThatThrownBy(() -> parser.check(sample("INSERT INTO sink SELECT FROM"), "image"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 1 条语句");
  }

  /** 精确 1.20.5 校验器运行初始 SQL，并拒绝实际语法错误。 */
  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "flink.test.validator.1205",
      matches = ".+")
  void exact1205ParserChecksInitialSqlAndRejectsInvalidSyntax() throws Exception {
    Files.createDirectories(directory.resolve("1.20.5"));
    Files.copy(
        Path.of(System.getProperty("flink.test.validator.1205")),
        directory.resolve("1.20.5/validator.jar"));
    var records = mock(RecordRepository.class);
    when(records.require("images", "image"))
        .thenReturn(mapper.createObjectNode().put("flinkVersion", "1.20.5"));
    var parser = new SqlParserServiceImpl(records, mapper, directory.toString());
    var statements =
        List.of(
            "CREATE TEMPORARY TABLE source_events (id BIGINT, payload STRING) WITH"
                + " ('connector'='datagen', 'rows-per-second'='1')",
            "CREATE TEMPORARY TABLE sink_events (id BIGINT, payload STRING) WITH"
                + " ('connector'='print')",
            "INSERT INTO sink_events SELECT id, payload FROM source_events");
    assertThat(
            parser.check(new SqlCheckResult(true, "BASIC", 3, 1, "", statements), "image").level())
        .isEqualTo("PARSER");
    assertThatThrownBy(() -> parser.check(sample("INSERT INTO sink SELECT FROM"), "image"))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("第 1 条语句");
  }

  /** 构建已经过基础结构检查的语句集合。 */
  private SqlCheckResult sample(String sql) {
    return new SqlCheckResult(true, "BASIC", 1, 1, "basic", List.of(sql));
  }
}
