package com.flinkweb.service.sql.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flinkweb.dto.sql.SqlCheckResult;
import com.flinkweb.exception.common.BusinessException;
import com.flinkweb.repository.metadata.RecordRepository;
import com.flinkweb.service.sql.SqlParserService;
import java.nio.file.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 按镜像的精确 Flink 版本启动隔离 Parser 进程；未安装适配器时明确降级为基础检查。 */
@Service
public class SqlParserServiceImpl implements SqlParserService {
  private final RecordRepository records;
  private final ObjectMapper mapper;
  private final Path directory;
  private final Semaphore slots = new Semaphore(2);

  /** 读取运维配置的校验器目录，目录不能由请求指定。 */
  public SqlParserServiceImpl(
      RecordRepository records,
      ObjectMapper mapper,
      @Value("${platform.sql-validation.directory:./sql-validators}") String directory) {
    this.records = records;
    this.mapper = mapper;
    this.directory = Path.of(directory).toAbsolutePath().normalize();
  }

  /** 查询已安装的适配器，返回真实文件存在情况。 */
  public java.util.List<String> installed() {
    if (!Files.isDirectory(directory)) return java.util.List.of();
    try (var files = Files.list(directory)) {
      return files
          .filter(
              p ->
                  p.getFileName().toString().matches("\\d+\\.\\d+\\.\\d+")
                      && Files.isRegularFile(p.resolve("validator.jar")))
          .map(p -> p.getFileName().toString())
          .sorted()
          .toList();
    } catch (Exception ex) {
      return java.util.List.of();
    }
  }

  /** 在十秒和 256 MiB 限制内执行 Parser，结构检查与版本语法检查分别呈现。 */
  public SqlCheckResult check(SqlCheckResult basic, String imageId) {
    if (imageId == null || imageId.isBlank()) return basic;
    String version = records.require("images", imageId).path("flinkVersion").asText();
    if (!version.matches("\\d+\\.\\d+\\.\\d+")) return fallback(basic, "镜像未登记精确补丁版本");
    Path jar = directory.resolve(version).resolve("validator.jar").normalize();
    if (!jar.startsWith(directory) || !Files.isRegularFile(jar))
      return fallback(basic, "未安装 Flink " + version + " Parser");
    if (!slots.tryAcquire())
      throw new BusinessException(
          HttpStatus.TOO_MANY_REQUESTS, "SQL_VALIDATION_BUSY", "SQL 校验繁忙，请稍后手动重试");
    Process process = null;
    try {
      String executable =
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString();
      ProcessBuilder builder = new ProcessBuilder(executable, "-Xmx256m", "-jar", jar.toString());
      builder.redirectError(ProcessBuilder.Redirect.DISCARD);
      // 校验进程不继承数据库/S3 等业务凭据；不加载用户 Connector。
      String systemRoot = builder.environment().get("SystemRoot");
      builder.environment().clear();
      if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
      process = builder.start();
      try (var out = process.getOutputStream()) {
        mapper.writeValue(
            out,
            mapper.createObjectNode().set("statements", mapper.valueToTree(basic.statements())));
      }
      if (!process.waitFor(10, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new BusinessException(
            HttpStatus.REQUEST_TIMEOUT, "SQL_VALIDATION_TIMEOUT", "SQL 语法校验超时");
      }
      var result = mapper.readTree(process.getInputStream().readNBytes(16384));
      if (process.exitValue() != 0 || !version.equals(result.path("version").asText()))
        throw new BusinessException(
            HttpStatus.BAD_GATEWAY, "PARSER_VERSION_MISMATCH", "SQL 校验器版本或运行结果不匹配");
      if (!result.path("valid").asBoolean())
        throw new BusinessException(
            HttpStatus.BAD_REQUEST,
            "SQL_PARSE_ERROR",
            "第 "
                + result.path("statement").asInt()
                + " 条语句，第 "
                + result.path("line").asInt()
                + " 行，第 "
                + result.path("column").asInt()
                + " 列："
                + result.path("message").asText());
      return new SqlCheckResult(
          true,
          "PARSER",
          basic.statementCount(),
          basic.insertCount(),
          "Flink " + version + " 语法检查通过；字段类型、Catalog 和连接器语义仍由运行时校验。",
          basic.statements());
    } catch (BusinessException ex) {
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new BusinessException(
          HttpStatus.SERVICE_UNAVAILABLE, "SQL_VALIDATION_INTERRUPTED", "SQL 校验已中断");
    } catch (Exception ex) {
      throw new BusinessException(
          HttpStatus.BAD_GATEWAY, "SQL_VALIDATOR_ERROR", "SQL 校验器运行失败，请检查安装目录和 JDK");
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
      slots.release();
    }
  }

  /** 未验证版本不冒充 Parser 通过，保留原基础检查结果。 */
  private SqlCheckResult fallback(SqlCheckResult basic, String reason) {
    return new SqlCheckResult(
        true,
        "BASIC",
        basic.statementCount(),
        basic.insertCount(),
        reason + "；仅通过基础检查，未完成该版本语法和语义验证。",
        basic.statements());
  }
}
