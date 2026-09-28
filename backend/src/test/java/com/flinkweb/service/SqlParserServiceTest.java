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
import org.junit.jupiter.api.io.TempDir;

/** 验证 Parser 版本降级，以及实际独立进程的语法检查协议。 */
class SqlParserServiceTest {
  @TempDir Path directory;
  final ObjectMapper mapper = new ObjectMapper();

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

  /** 构建已经过基础结构检查的语句集合。 */
  private SqlCheckResult sample(String sql) {
    return new SqlCheckResult(true, "BASIC", 1, 1, "basic", List.of(sql));
  }
}
